"""
Syna ASR Server — 整句麦克风语音识别服务
监听麦克风 → 录完整句 → 一次性发送火山 ASR → 拿到完整结果 → 发送给 Mindcraft bot

用法：
  python services/syna_asr_server.py
  python services/syna_asr_server.py --default-agent syna --rms-threshold 600
"""

import argparse
import asyncio
import gzip
import io
import json
import math
import os
import re
import struct
import sys
import threading
import time
import uuid
import wave
from collections import deque
from pathlib import Path

try:
    import pyaudio
except ImportError:
    pyaudio = None
    print("❌ 缺少 pyaudio，无法监听麦克风：pip install pyaudio")

try:
    import websockets
except ImportError:
    websockets = None
    print("❌ 缺少 websockets，无法连接火山 ASR：pip install websockets")

try:
    import requests
except ImportError:
    requests = None
    print("❌ 缺少 requests，无法转发到 Mindcraft：pip install requests")


# ==========================================
# 配置加载
# ==========================================

def load_keys_config():
    candidates = [
        Path(__file__).resolve().parent.parent / "keys.json",
        Path.cwd() / "keys.json",
    ]
    for path in candidates:
        try:
            if path.exists():
                with path.open("r", encoding="utf-8") as f:
                    data = json.load(f)
                if isinstance(data, dict):
                    return data, path
        except Exception as e:
            print(f"⚠️ [SynaASR] 读取 keys.json 失败 {path}: {e}", flush=True)
    return {}, None


def load_default_agent_name():
    settings_candidates = [
        Path(__file__).resolve().parent.parent / "settings.js",
        Path.cwd() / "settings.js",
    ]
    for settings_path in settings_candidates:
        try:
            if not settings_path.exists():
                continue
            text = settings_path.read_text(encoding="utf-8", errors="ignore")
            profile_match = re.search(r'"profiles"\s*:\s*\[\s*"([^\"]+)"', text)
            if not profile_match:
                continue
            profile_rel = profile_match.group(1).strip()
            profile_path = (settings_path.parent / profile_rel).resolve()
            if not profile_path.exists():
                continue
            profile = json.loads(profile_path.read_text(encoding="utf-8", errors="ignore"))
            name = str(profile.get("name", "")).strip()
            if name:
                return name
        except Exception:
            pass
    return ""


# ==========================================
# 火山 ASR 协议工具
# ==========================================

def build_ws_header(message_type, specific_flags, serial_method, compression):
    """构建火山 WebSocket 二进制帧头"""
    b0 = 0x11
    b1 = (message_type << 4) | specific_flags
    b2 = (serial_method << 4) | compression
    b3 = 0x00
    return bytes([b0, b1, b2, b3])


def parse_volc_payload(res):
    """解析火山 WebSocket payload"""
    if not isinstance(res, (bytes, bytearray)) or len(res) < 12:
        return None
    compression = res[2] & 0x0F
    payload_size = struct.unpack(">I", res[8:12])[0]
    payload = res[12:12 + payload_size]
    if not payload:
        return None
    try:
        if compression == 1:
            payload = gzip.decompress(payload)
        text = payload.decode("utf-8", errors="ignore")
        return json.loads(text)
    except Exception:
        return {"raw": repr(payload[:300])}


def is_weak_voice_text(text):
    """过滤嗯嗯嗯/啊/纯标点等弱输入"""
    clean = re.sub(r"\s+", "", str(text or ""))
    clean = re.sub(r"[。！？!?，,、.．…~～\-]+", "", clean)
    if not clean:
        return True
    if len(clean) <= 1:
        return True
    filler_chars = set("嗯呃额啊唔哦噢诶哎哈呵嘿哼呜")
    if len(clean) <= 6 and all(ch in filler_chars for ch in clean):
        return True
    weak_words = {"嗯嗯", "嗯嗯嗯", "啊啊", "呃呃", "哈哈", "哈哈哈", "好的", "好吧"}
    return clean in weak_words


# ==========================================
# 整句 ASR 识别（录完再发）
# ==========================================

def build_wav_bytes(frames, sample_rate=16000, bits=16, channels=1):
    """将 PCM frames 列表拼成完整 WAV 字节"""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(bits // 8)
        wf.setframerate(sample_rate)
        wf.writeframes(b"".join(frames))
    return buf.getvalue()



def resample_frames_pcm16_mono(frames, source_rate, target_rate):
    """Resample 16-bit mono PCM frames to the ASR sample rate."""
    source_rate = int(source_rate or target_rate or 16000)
    target_rate = int(target_rate or source_rate or 16000)
    raw = b"".join(frames or [])
    if not raw or source_rate == target_rate:
        return [raw] if raw else []

    try:
        import audioop
        converted, _ = audioop.ratecv(raw, 2, 1, source_rate, target_rate, None)
        return [converted] if converted else []
    except Exception:
        # Fallback: nearest-neighbor resampling. Good enough for diagnostic voice capture.
        samples = struct.unpack("<%dh" % (len(raw) // 2), raw)
        if not samples:
            return []
        out_count = max(1, int(len(samples) * target_rate / source_rate))
        ratio = source_rate / target_rate
        out = bytearray()
        for i in range(out_count):
            src_index = min(len(samples) - 1, int(i * ratio))
            out += struct.pack("<h", samples[src_index])
        return [bytes(out)]


async def _recognize_full_audio_once(service, wav_data, chunk_size=6400, chunk_sleep=0.0, label="fast"):
    """
    一次性将完整 WAV 音频发送给火山 ASR，等待最终识别结果。
    返回识别文本或 None。
    """
    url = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    req_id = str(uuid.uuid4())
    headers = {
        "X-Api-App-Key": service.app_id,
        "X-Api-Access-Key": service.access_token,
        "X-Api-Resource-Id": service.resource_id,
        "X-Api-Request-Id": req_id,
        "X-Api-Sequence": "-1",
    }

    try:
        try:
            ws = await websockets.connect(url, additional_headers=headers)
        except TypeError:
            ws = await websockets.connect(url, extra_headers=headers)
    except Exception as e:
        print(f"❌ [ASR] WebSocket 连接失败: {e}", flush=True)
        service.diag("volc_connect_failed", requestId=req_id, error=str(e), errorType=type(e).__name__)
        return None

    try:
        # 1. 发送配置帧
        config_payload = {
            "user": {"uid": "syna_mc_asr"},
            "audio": {"format": "wav", "codec": "pcm", "rate": 16000, "bits": 16, "channel": 1},
            "request": {"model_name": "bigmodel", "enable_itn": True, "result_type": "full"},
        }
        config_gz = gzip.compress(json.dumps(config_payload, ensure_ascii=False).encode("utf-8"))
        header = build_ws_header(1, 0, 1, 1)
        await ws.send(header + struct.pack(">I", len(config_gz)) + config_gz)

        # Match the proven NeuroSama framing: audio chunks, then an empty final frame.
        for i in range(0, len(wav_data), chunk_size):
            chunk = wav_data[i:i + chunk_size]
            chunk_gz = gzip.compress(chunk)
            audio_header = build_ws_header(2, 0, 0, 1)
            await ws.send(audio_header + struct.pack(">I", len(chunk_gz)) + chunk_gz)
            if chunk_sleep > 0:
                await asyncio.sleep(chunk_sleep)

        final_gz = gzip.compress(b"")
        final_header = build_ws_header(2, 2, 0, 1)
        await ws.send(final_header + struct.pack(">I", len(final_gz)) + final_gz)
        service.diag("volc_audio_sent", requestId=req_id, mode=label, chunkSize=chunk_size, chunkSleep=chunk_sleep)

        # 3. 等待最终识别结果
        final_text = ""
        partial_text = ""
        while True:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=14)
            except asyncio.TimeoutError:
                print("⚠️ [ASR] 等待超时", flush=True)
                service.diag("volc_timeout", requestId=req_id, partialText=partial_text, mode=label)
                break
            if not isinstance(msg, (bytes, bytearray)) or len(msg) < 4:
                continue
            msg_type = msg[1] >> 4
            specific_flags = msg[1] & 0x0F

            if msg_type == 9:
                if len(msg) < 12:
                    continue
                res_dict = parse_volc_payload(msg)
                if not isinstance(res_dict, dict):
                    continue
                result = res_dict.get("result") or {}
                text = result.get("text", "")
                if text:
                    partial_text = text
                # specific_flags == 3 表示最终结果
                if specific_flags == 3:
                    final_text = text or partial_text
                    break
            elif msg_type == 15:
                detail = parse_volc_payload(msg) or repr(msg[:120])
                print(f"❌ [ASR] 火山报错: {detail}", flush=True)
                service.diag("volc_error", requestId=req_id, detail=detail)
                break

        return final_text.strip() if final_text else (partial_text.strip() or None)

    except websockets.exceptions.ConnectionClosed:
        service.diag("volc_connection_closed", requestId=req_id)
        print("⚠️ [ASR] WebSocket 连接被关闭", flush=True)
        return partial_text.strip() if partial_text else None
    except Exception as e:
        print(f"❌ [ASR] 识别异常: {e}", flush=True)
        service.diag("volc_exception", requestId=req_id, error=str(e), errorType=type(e).__name__)
        return None
    finally:
        try:
            await ws.close()
        except Exception:
            pass


async def recognize_full_audio(service, wav_data):
    """Use the stable NeuroSama fast request plus paced retry strategy."""
    attempts = [
        (6400, 0.0, "neuro_fast"),
        (3200, 0.006, "neuro_paced_retry"),
    ]
    for index, (chunk_size, chunk_sleep, label) in enumerate(attempts):
        text = await _recognize_full_audio_once(
            service,
            wav_data,
            chunk_size=chunk_size,
            chunk_sleep=chunk_sleep,
            label=label,
        )
        if text:
            return text
        if index + 1 < len(attempts):
            service.diag("volc_retry", previousMode=label, reason="empty_result")
            print(f"[SynaASR] empty ASR result in {label}; retrying with paced Neuro framing", flush=True)
    return None


# ==========================================
# 服务主类
# ==========================================


def list_input_devices(p):
    devices = []
    try:
        count = p.get_device_count()
    except Exception as e:
        print(f"[SynaASR] failed to enumerate audio devices: {e}", flush=True)
        return devices
    for i in range(count):
        try:
            info = p.get_device_info_by_index(i)
            if int(info.get("maxInputChannels", 0) or 0) <= 0:
                continue
            host_api_index = int(info.get("hostApi", -1) or -1)
            host_api_name = ""
            try:
                host_api_name = str(p.get_host_api_info_by_index(host_api_index).get("name", ""))
            except Exception:
                pass
            devices.append({
                "index": i,
                "name": str(info.get("name", "")),
                "channels": int(info.get("maxInputChannels", 0) or 0),
                "sample_rate": int(float(info.get("defaultSampleRate", 0) or 0)),
                "host_api": host_api_name,
                "host_api_index": host_api_index,
            })
        except Exception as e:
            print(f"[SynaASR] failed to read device {i}: {e}", flush=True)
    return devices


def _canonical_device_name(name):
    text = re.sub(r"\s+", " ", str(name or "")).strip().lower()
    text = text.replace("（", "(").replace("）", ")")
    text = re.sub(r"\s*\([^)]*\)\s*$", "", text)
    text = text.replace("microsoft 声音映射器 - input", "default")
    text = text.replace("microsoft sound mapper - input", "default")
    for prefix in ("麦克风阵列", "麦克风", "microphone array", "microphone", "input"):
        text = re.sub(rf"^{re.escape(prefix)}\s*", "", text).strip()
    return text or "input"


def _device_score(device, default_index=None, preferred_name=""):
    name = str(device.get("name", ""))
    host = str(device.get("host_api", ""))
    lower = f"{name} {host}".lower()
    score = 0
    if default_index is not None and int(device.get("index", -1)) == int(default_index):
        score += 260
    if preferred_name and preferred_name.lower() in lower:
        score += 700
    if "simgot" in lower:
        score += 300
    if "microphone" in lower or "麦克风" in lower or "mic " in lower:
        score += 160
    if "wasapi" in lower:
        score += 520
    if "directsound" in lower:
        score += 240
    if "wdm-ks" in lower:
        score += 40
    if "mme" in lower:
        score -= 20
    if not host:
        score -= 20
    if "mapper" in lower or "映射器" in lower:
        score -= 250
    if "cable output" in lower or "vb-audio" in lower or "sonar" in lower:
        score -= 40
    rate = int(device.get("sample_rate", 0) or 0)
    if rate == 16000:
        score += 25
    elif rate == 48000:
        score += 15
    elif rate == 44100:
        score += 10
    channels = int(device.get("channels", 0) or 0)
    if channels == 1:
        score += 20
    elif channels == 2:
        score += 15
    elif channels > 2:
        score -= 60
    return score


def select_input_devices(devices, default_index=None, preferred_name="", max_devices=1):
    grouped = {}
    for device in devices:
        key = _canonical_device_name(device.get("name", ""))
        candidate = grouped.get(key)
        if candidate is None or _device_score(device, default_index, preferred_name) > _device_score(candidate, default_index, preferred_name):
            grouped[key] = device
    selected = sorted(
        grouped.values(),
        key=lambda d: _device_score(d, default_index, preferred_name),
        reverse=True,
    )
    return selected[:max(1, int(max_devices or 1))]


def print_input_devices():
    if pyaudio is None:
        print("[SynaASR] pyaudio is not installed; cannot list microphones.", flush=True)
        return 1
    p = pyaudio.PyAudio()
    try:
        devices = list_input_devices(p)
        default_index = None
        try:
            default_info = p.get_default_input_device_info()
            default_index = int(default_info.get("index"))
        except Exception:
            pass
        print("[SynaASR] Input devices:", flush=True)
        if not devices:
            print("  (no input devices found)", flush=True)
        for d in devices:
            star = "*" if d["index"] == default_index else " "
            host = f", {d.get('host_api', '')}" if d.get('host_api') else ""
            print(f"{star} {d['index']}: {d['name']} ({d['channels']} ch, default {d['sample_rate']} Hz{host})", flush=True)
        return 0
    finally:
        p.terminate()
class SynaASRService:
    def __init__(self, args):
        self.args = args
        self.keys_config, self.keys_path = load_keys_config()
        self.default_agent = args.default_agent or load_default_agent_name()
        self.is_running = True
        self.last_send_time = 0
        # Mute flag: when True, mic input is ignored (used during TTS playback to prevent echo loop)
        self.muted = False
        self._last_interrupt_at = 0.0
        self.input_device_index = args.input_device
        self.input_device_name = ""
        self.input_devices = []
        self._send_lock = threading.Lock()
        self._recent_sent_texts = {}
        self._mic_status_lock = threading.Lock()
        self.mic_status = {}
        self._diag_lock = threading.Lock()
        self.diag_events = deque(maxlen=int(getattr(args, "diag_events", 400) or 400))
        self.diag_dir = Path(getattr(args, "diag_dir", "") or (Path(__file__).resolve().parent.parent / "logs"))
        self.diag_dir.mkdir(parents=True, exist_ok=True)
        self.diag_path = self.diag_dir / f"asr_diag_{time.strftime('%Y%m%d_%H%M%S')}.jsonl"
        self.focus_gate_status = {"allowed": not args.require_focus, "reason": "startup", "time": time.time()}
        self.focus_required = bool(args.require_focus)
        self.focus_window_seconds = max(0.0, float(args.focus_window_seconds or 0.0))
        self.ptt_mode = bool(getattr(args, "push_to_talk", False))
        self.neuro_capture = bool(getattr(args, "neuro_capture", False))
        self._ptt_lock = threading.Lock()
        self._ptt_active = False
        self._ptt_session = 0

        # 从 keys.json 或参数获取火山配置
        cfg = self.keys_config or {}
        self.app_id = args.volc_app_id or os.getenv("VOLC_APP_ID") or cfg.get("VOLC_APP_ID", "")
        self.access_token = args.volc_access_token or os.getenv("VOLC_ACCESS_TOKEN") or cfg.get("VOLC_ACCESS_TOKEN", "")
        self.resource_id = args.volc_asr_resource_id or os.getenv("VOLC_ASR_RESOURCE_ID") or cfg.get("VOLC_ASR_RESOURCE_ID", "volc.seedasr.sauc.duration")

        if not self.app_id or not self.access_token:
            print("⚠️ [SynaASR] 未配置 VOLC_APP_ID / VOLC_ACCESS_TOKEN，ASR 将无法工作！", flush=True)
            print("   请在 keys.json 中添加或通过启动参数传入。", flush=True)

    def diag(self, event, **fields):
        payload = {"time": time.time(), "event": str(event)}
        payload.update(fields)
        with self._diag_lock:
            self.diag_events.append(payload)
            try:
                with self.diag_path.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(payload, ensure_ascii=False, default=str) + "\n")
            except Exception as e:
                print(f"[SynaASRDiag] failed to write diag: {e}", flush=True)
        return payload

    def update_mic_status(self, device_key, **fields):
        now = time.time()
        key = str(device_key)
        with self._mic_status_lock:
            status = self.mic_status.setdefault(key, {"device": key, "peakRms": 0.0, "speechStarts": 0})
            status.update(fields)
            if "lastRms" in fields:
                status["peakRms"] = max(float(status.get("peakRms", 0.0) or 0.0), float(fields.get("lastRms", 0.0) or 0.0))
            status["updatedAt"] = now

    def mark_speech_start(self, device_key):
        key = str(device_key)
        with self._mic_status_lock:
            status = self.mic_status.setdefault(key, {"device": key, "peakRms": 0.0, "speechStarts": 0})
            status["speechStarts"] = int(status.get("speechStarts", 0) or 0) + 1
            status["lastSpeechAt"] = time.time()
            status["recording"] = True
            status["event"] = "speech_detected"

    def set_focus_gate_status(self, allowed, reason, detail=""):
        self.focus_gate_status = {
            "allowed": bool(allowed),
            "reason": str(reason),
            "detail": str(detail or ""),
            "time": time.time(),
        }

    def ptt_start(self):
        with self._ptt_lock:
            if not self._ptt_active:
                self._ptt_session += 1
                self._ptt_active = True
                changed = True
            else:
                changed = False
            session = self._ptt_session
        self.muted = False
        if changed:
            self.diag("ptt_start", session=session)
            print(f"[SynaASR] PTT started (session {session})", flush=True)
        return {"ok": True, "pttMode": self.ptt_mode, "pttActive": True, "pttSession": session, "changed": changed}

    def ptt_stop(self):
        with self._ptt_lock:
            changed = self._ptt_active
            self._ptt_active = False
            session = self._ptt_session
        if changed:
            self.diag("ptt_stop", session=session)
            print(f"[SynaASR] PTT released (session {session})", flush=True)
        return {"ok": True, "pttMode": self.ptt_mode, "pttActive": False, "pttSession": session, "changed": changed}

    def ptt_snapshot(self):
        with self._ptt_lock:
            return self._ptt_active, self._ptt_session

    def status_payload(self):
        with self._mic_status_lock:
            devices = [dict(v) for v in self.mic_status.values()]
        devices.sort(key=lambda d: str(d.get("device", "")))
        ptt_active, ptt_session = self.ptt_snapshot()
        freshest_read = max((float(d.get("lastReadAt", 0.0) or 0.0) for d in devices), default=0.0)
        stale_seconds = time.time() - freshest_read if freshest_read > 0 else float("inf")
        stream_healthy = any(bool(d.get("streamOpen")) for d in devices) and stale_seconds < 3.0
        return {
            "ok": True,
            "healthy": stream_healthy,
            "staleSeconds": round(stale_seconds, 3) if math.isfinite(stale_seconds) else None,
            "diagPath": str(self.diag_path),
            "muted": self.muted,
            "pttMode": self.ptt_mode,
            "pureMod": bool(getattr(self.args, "pure_mod", False)),
            "pttActive": ptt_active,
            "pttSession": ptt_session,
            "captureMode": "neuro_default_16k" if self.neuro_capture else "configured_device",
            "inputDeviceIndex": self.input_device_index,
            "inputDeviceName": self.input_device_name,
            "inputDevices": self.input_devices,
            "micStatus": devices,
            "focusRequired": self.focus_required,
            "alwaysListen": not self.focus_required and not self.ptt_mode,
            "focusGate": self.focus_gate_status,
            "lastSendTime": self.last_send_time,
            "rmsThreshold": self.args.rms_threshold,
            "sampleRate": self.args.sample_rate,
            "allInputDevices": bool(getattr(self.args, "all_input_devices", False)),
            "smartDeviceSelection": bool(getattr(self.args, "smart_input_devices", True)),
        }

    def diag_payload(self):
        payload = self.status_payload()
        with self._diag_lock:
            payload["recentEvents"] = list(self.diag_events)
        return payload

    def interrupt_tts_on_speech_start(self):
        if requests is None:
            return False
        base_url = str(getattr(self.args, "voice_base_url", "") or "http://127.0.0.1:8766").rstrip("/")
        now = time.time()
        if now - self._last_interrupt_at < 0.6:
            return False
        self._last_interrupt_at = now
        url = f"{base_url}/interrupt"
        self.diag("tts_interrupt_scheduled", url=url)

        def _post_interrupt():
            try:
                requests.post(url, json={}, timeout=0.25)
                print(f"[ASR->TTS] interrupt requested: {url}", flush=True)
                self.diag("tts_interrupt", ok=True, url=url)
            except Exception as e:
                print(f"[ASR->TTS] interrupt failed: {e}", flush=True)
                self.diag("tts_interrupt", ok=False, error=str(e), url=url)

        threading.Thread(target=_post_interrupt, daemon=True, name="syna-asr-tts-interrupt").start()
        return True
    def recently_focused(self):
        if not self.focus_required:
            self.set_focus_gate_status(True, "always_listen")
            return True
        if requests is None:
            self.set_focus_gate_status(False, "requests_missing")
            return False
        try:
            base_url = str(getattr(self.args, "mod_bridge_url", "") or "http://127.0.0.1:8765").rstrip("/")
            resp = requests.get(f"{base_url}/state", timeout=0.35)
            data = resp.json()
            tick = int(data.get("tick", 0) or 0)
            events = list(data.get("debugLog", []) or [])
            for event in reversed(events):
                text = str(event)
                if "voice_focus:" not in text:
                    continue
                try:
                    event_tick = int(text.split(":", 1)[0])
                except Exception:
                    self.set_focus_gate_status(True, "focus_event_without_tick", text)
                    return True
                allowed = (tick - event_tick) <= int(self.focus_window_seconds * 20)
                self.set_focus_gate_status(allowed, "recent_focus" if allowed else "focus_expired", f"tick={tick} event_tick={event_tick}")
                return allowed
        except Exception as e:
            self.set_focus_gate_status(False, "bridge_error", str(e))
            print(f"[SynaASR] focus gate check failed: {e}", flush=True)
            return False
        self.set_focus_gate_status(False, "no_focus_event")
        return False

    def should_forward_text(self, text):
        normalized = re.sub(r"\s+", "", str(text or "")).strip()
        if not normalized:
            return False
        now = time.time()
        window = float(getattr(self.args, "dedupe_seconds", 4.0) or 0.0)
        with self._send_lock:
            self._recent_sent_texts = {
                k: t for k, t in self._recent_sent_texts.items()
                if now - t < window
            }
            last_seen = self._recent_sent_texts.get(normalized)
            if window > 0 and last_seen and now - last_seen < window:
                print(f"[SynaASR] duplicate ASR text ignored: {text}", flush=True)
                return False
            self._recent_sent_texts[normalized] = now
        return True

    def send_to_mindcraft(self, text):
        """将识别结果发送给 Mindcraft bot"""
        if requests is None:
            print("❌ [SynaASR] requests 未安装，无法转发", flush=True)
            return False

        if bool(getattr(self.args, "pure_mod", False)):
            url = str(getattr(self.args, "mod_bridge_url", "") or "http://127.0.0.1:8765").rstrip("/") + "/input"
            try:
                resp = requests.post(url, json={"player": self.args.sender_name, "text": text}, timeout=5.0)
                resp.raise_for_status()
                self.last_send_time = time.time()
                print(f"[ASR->MOD] sent: {text[:50]}", flush=True)
                return True
            except Exception as e:
                print(f"[ASR->MOD] forwarding failed: {e}", flush=True)
                return False

        agent_name = self.default_agent
        if not agent_name:
            print("⚠️ [SynaASR] 未设置 agent_name，无法转发。请用 --default-agent 指定。", flush=True)
            return False

        base_url = self.args.mindcraft_url.rstrip("/")

        # 尝试 voice-input 端点
        url = f"{base_url}/api/agents/{agent_name}/voice-input"
        payload = {"from": self.args.sender_name, "text": text}

        try:
            resp = requests.post(url, json=payload, timeout=5.0)
            resp.raise_for_status()
            self.last_send_time = time.time()
            print(f"✅ [ASR->MC] 已发送: {text[:50]}", flush=True)
            return True
        except (requests.exceptions.ConnectionError, requests.exceptions.HTTPError):
            # 回退到 message 端点
            url_fallback = f"{base_url}/api/agents/{agent_name}/message"
            payload_fallback = {"from": self.args.sender_name, "message": text}
            try:
                resp = requests.post(url_fallback, json=payload_fallback, timeout=5.0)
                resp.raise_for_status()
                self.last_send_time = time.time()
                print(f"✅ [ASR->MC] 已发送(fallback): {text[:50]}", flush=True)
                return True
            except Exception as e:
                print(f"❌ [ASR->MC] 转发失败: {e}", flush=True)
                return False
        except Exception as e:
            print(f"❌ [ASR->MC] 转发失败: {e}", flush=True)
            return False


# ==========================================
# 整句麦克风监听（录完再识别）
# ==========================================

class MicRecordManager:
    """Manage microphone recording and full-sentence ASR recognition."""

    def __init__(self, service, loop):
        self.service = service
        self.loop = loop
        self.args = service.args

    def run(self):
        if pyaudio is None:
            print("[SynaASR] pyaudio is not installed", flush=True)
            return
        if self.args.all_input_devices and self.args.input_device is None:
            self._run_all_input_devices()
            return
        self._run_single_device(self.args.input_device)

    def _run_all_input_devices(self):
        active = {}
        rescan_seconds = max(1.0, float(getattr(self.args, "device_rescan_seconds", 10.0) or 10.0))
        while self.service.is_running:
            p = pyaudio.PyAudio()
            try:
                devices = list_input_devices(p)
                default_index = None
                try:
                    default_index = int(p.get_default_input_device_info().get("index"))
                except Exception:
                    pass
                selected = select_input_devices(
                    devices,
                    default_index=default_index,
                    preferred_name=str(getattr(self.args, "preferred_input_name", "") or ""),
                    max_devices=int(getattr(self.args, "max_active_input_devices", 1) or 1),
                ) if getattr(self.args, "smart_input_devices", True) else devices
                self.service.input_devices = devices
                self.service.input_device_index = None
                self.service.input_device_name = ", ".join(f"{d['index']}:{d['name']}" for d in selected)
                live_indexes = {int(d["index"]) for d in selected}
                print(f"[SynaASR] input scan: found {len(devices)} input endpoint(s), listening on {len(selected)} selected endpoint(s)", flush=True)
                self.service.diag("device_scan", found=len(devices), selected=[{k: d.get(k) for k in ("index", "name", "sample_rate", "host_api", "channels")} for d in selected], defaultIndex=default_index)
                for d in devices:
                    idx = int(d["index"])
                    selected_flag = idx in live_indexes
                    self.service.update_mic_status(idx, label=f"{idx}:{d['name']}", channels=d.get("channels"), defaultSampleRate=d.get("sample_rate"), hostApi=d.get("host_api"), present=True, selected=selected_flag)
                for d in selected:
                    idx = int(d["index"])
                    thread = active.get(idx)
                    if thread is not None and thread.is_alive():
                        continue
                    print(f"[SynaASR] starting listener on selected endpoint {idx}: {d['name']} ({d['channels']} ch, default {d['sample_rate']} Hz, {d.get('host_api', 'unknown')})", flush=True)
                    t = threading.Thread(
                        target=self._run_single_device,
                        args=(idx,),
                        daemon=True,
                        name=f"syna-asr-mic-{idx}",
                    )
                    t.start()
                    active[idx] = t
                for idx in list(active.keys()):
                    if idx not in live_indexes:
                        self.service.update_mic_status(idx, present=False, selected=False, event="device_not_selected_after_rescan")
            except Exception as e:
                print(f"[SynaASR] failed to enumerate input devices: {e}", flush=True)
            finally:
                p.terminate()

            deadline = time.time() + rescan_seconds
            while self.service.is_running and time.time() < deadline:
                time.sleep(0.5)

    def _run_single_device(self, requested_device_index):
        while self.service.is_running:
            restart = self._listen_on_device_once(requested_device_index)
            if not restart or not self.service.is_running:
                return
            time.sleep(1.5)

    def _listen_on_device_once(self, requested_device_index):
        p = pyaudio.PyAudio()
        stream = None
        input_device_index = requested_device_index
        device_label = "default"
        try:
            if input_device_index is not None:
                info = p.get_device_info_by_index(input_device_index)
            else:
                info = p.get_default_input_device_info()
                input_device_index = int(info.get("index"))
            device_name = str(info.get("name", ""))
            device_label = f"{input_device_index}:{device_name}"
            if not self.args.all_input_devices:
                self.service.input_device_index = input_device_index
                self.service.input_device_name = device_name
            device_sample_rate = int(self.args.sample_rate) if self.service.neuro_capture else int(float(info.get("defaultSampleRate", self.args.sample_rate) or self.args.sample_rate))
            if device_sample_rate <= 0:
                device_sample_rate = int(self.args.sample_rate)
            print(f"[SynaASR] listening on input device {device_label} at {device_sample_rate}Hz", flush=True)
            self.service.diag("device_selected", device=input_device_index, label=device_label, captureRate=device_sample_rate, hostApi=str(info.get("hostApi", "")))
        except Exception as e:
            print(f"[SynaASR] failed to select input device {requested_device_index}: {e}", flush=True)
            self.service.update_mic_status(requested_device_index if requested_device_index is not None else "default", event="select_failed", error=str(e), streamOpen=False)
            print_input_devices()
            p.terminate()
            return True

        try:
            stream = p.open(
                format=pyaudio.paInt16,
                channels=1,
                rate=device_sample_rate,
                input=True,
                input_device_index=input_device_index,
                frames_per_buffer=self.args.chunk_size,
            )
        except Exception as e:
            print(f"[SynaASR] failed to open input device {device_label}: {e}", flush=True)
            self.service.update_mic_status(input_device_index, label=device_label, event="open_failed", error=str(e), streamOpen=False, requestedRate=device_sample_rate)
            self.service.diag("device_open_failed", device=input_device_index, label=device_label, requestedRate=device_sample_rate, error=str(e))
            p.terminate()
            return True

        end_silence_seconds = self.args.end_silence_frames * self.args.chunk_size / device_sample_rate
        if not self.args.all_input_devices or requested_device_index == self.service.input_devices[0]["index"]:
            print("[SynaASR] microphone listener started", flush=True)
            print(f"   capture rate: {device_sample_rate}Hz -> ASR {self.args.sample_rate}Hz, end silence: {end_silence_seconds:.1f}s, RMS threshold: {self.args.rms_threshold}", flush=True)
            print(f"   target Mindcraft: {self.args.mindcraft_url}, agent: {self.service.default_agent or '(not set)'}", flush=True)
            print("   mode: push-to-talk (hold V, release to send)" if self.service.ptt_mode else "   mode: automatic full sentence ASR", flush=True)
            print("", flush=True)

        is_recording = False
        recording_started_at = 0.0
        recording_ptt_session = 0
        completed_ptt_session = 0
        silence_count = 0
        frames = []
        read_errors = 0
        last_status_update = 0.0
        self.service.update_mic_status(input_device_index, label=device_label, event="stream_open", streamOpen=True, recording=False, readErrors=0, captureRate=device_sample_rate, asrRate=self.args.sample_rate)
        self.service.diag("stream_open", device=input_device_index, label=device_label, captureRate=device_sample_rate, asrRate=self.args.sample_rate, threshold=self.args.rms_threshold)

        def submit_recording(recorded_frames, reason="silence"):
            self.service.update_mic_status(input_device_index, label=device_label, event="recognizing", recording=False)
            speech_duration = len(recorded_frames) * self.args.chunk_size / device_sample_rate
            event_name = "speech_forced_end" if reason != "silence" else "speech_end"
            self.service.diag(event_name, device=input_device_index, label=device_label, frames=len(recorded_frames), duration=round(speech_duration, 3), captureRate=device_sample_rate, reason=reason)
            print(f"[ASR:{device_label}] speech ended ({reason}); {len(recorded_frames)} frame(s), recognizing...", flush=True)
            self.service.diag("recognition_scheduled", device=input_device_index, label=device_label, frames=len(recorded_frames), captureRate=device_sample_rate, reason=reason)
            future = asyncio.run_coroutine_threadsafe(
                self._recognize_and_send(recorded_frames, device_label, device_sample_rate), self.loop
            )
            future.add_done_callback(lambda fut, label=device_label: self._log_recognition_future(label, fut))

        max_recording_seconds = max(0.0, float(getattr(self.args, "max_recording_seconds", 8.0) or 0.0))

        while self.service.is_running:
            try:
                data = stream.read(self.args.chunk_size, exception_on_overflow=False)
                read_errors = 0
            except Exception as e:
                read_errors += 1
                self.service.update_mic_status(input_device_index, label=device_label, event="read_error", error=str(e), readErrors=read_errors, streamOpen=True)
                if read_errors >= 20:
                    print(f"[SynaASR] input device {device_label} stopped responding; reopening ({e})", flush=True)
                    break
                time.sleep(0.02)
                continue

            samples = struct.unpack("%dh" % (len(data) // 2), data)
            rms = math.sqrt(sum(s ** 2 for s in samples) / len(samples))
            now = time.time()
            if now - last_status_update >= 0.5:
                self.service.update_mic_status(input_device_index, label=device_label, event="reading", lastReadAt=now, lastRms=round(rms, 2), recording=is_recording, readErrors=read_errors, streamOpen=True, captureRate=device_sample_rate, asrRate=self.args.sample_rate)
                last_status_update = now

            if self.service.ptt_mode:
                ptt_active, ptt_session = self.service.ptt_snapshot()
                if ptt_active and ptt_session != completed_ptt_session:
                    if not is_recording or recording_ptt_session != ptt_session:
                        if is_recording and frames:
                            submit_recording(frames, reason="ptt_restarted")
                        is_recording = True
                        recording_started_at = now
                        recording_ptt_session = ptt_session
                        frames = []
                        self.service.mark_speech_start(input_device_index)
                        self.service.diag("ptt_capture_start", device=input_device_index, label=device_label, session=ptt_session)
                        self.service.interrupt_tts_on_speech_start()
                        print(f"[ASR:{device_label}] V held; recording PTT session {ptt_session}...", flush=True)
                    frames.append(data)

                    elapsed = now - recording_started_at
                    if max_recording_seconds > 0 and elapsed >= max_recording_seconds:
                        is_recording = False
                        completed_ptt_session = ptt_session
                        recorded_frames = frames
                        frames = []
                        submit_recording(recorded_frames, reason="ptt_max_duration")
                else:
                    if is_recording:
                        is_recording = False
                        recorded_frames = frames
                        frames = []
                        submit_recording(recorded_frames, reason="ptt_release")
                    if not ptt_active:
                        recording_ptt_session = 0
                continue

            if rms > self.args.rms_threshold:
                silence_count = 0
                if self.service.muted:
                    continue
                if not is_recording:
                    is_recording = True
                    recording_started_at = now
                    frames = []
                    self.service.mark_speech_start(input_device_index)
                    self.service.diag("speech_start", device=input_device_index, label=device_label, rms=round(rms, 2), threshold=self.args.rms_threshold)
                    self.service.interrupt_tts_on_speech_start()
                    print(f"[ASR:{device_label}] speech detected; recording...", flush=True)
                frames.append(data)

            elif is_recording:
                silence_count += 1
                frames.append(data)

            if is_recording:
                elapsed = now - recording_started_at if recording_started_at else 0.0
                should_end_for_silence = silence_count >= self.args.end_silence_frames
                should_force_end = max_recording_seconds > 0 and elapsed >= max_recording_seconds
                if should_end_for_silence or should_force_end:
                    is_recording = False
                    reason = "max_recording_seconds" if should_force_end and not should_end_for_silence else "silence"
                    recorded_frames = frames
                    frames = []
                    silence_count = 0
                    submit_recording(recorded_frames, reason=reason)

        try:
            if stream is not None:
                stream.stop_stream()
                stream.close()
        except Exception:
            pass
        self.service.update_mic_status(input_device_index if input_device_index is not None else requested_device_index, label=device_label, event="stream_closed", streamOpen=False, recording=False)
        p.terminate()
        return self.service.is_running

    def _log_recognition_future(self, device_label, future):
        try:
            future.result()
        except Exception as e:
            self.service.diag("asr_task_failed", label=device_label, error=repr(e), errorType=type(e).__name__)
            print(f"[ASR:{device_label}] recognition task failed: {e!r}", flush=True)

    async def _recognize_and_send(self, frames, device_label="mic", capture_rate=None):
        capture_rate = int(capture_rate or self.args.sample_rate)
        duration = len(frames) * self.args.chunk_size / capture_rate
        asr_frames = resample_frames_pcm16_mono(frames, capture_rate, self.args.sample_rate)
        wav_data = build_wav_bytes(asr_frames, sample_rate=self.args.sample_rate)
        print(f"[ASR:{device_label}] audio duration: {duration:.1f}s, capture={capture_rate}Hz, asr={self.args.sample_rate}Hz, size: {len(wav_data)//1024}KB", flush=True)

        if duration < 0.2:
            self.service.diag("asr_skip", reason="too_short", label=device_label, duration=round(duration, 3))
            print(f"[ASR:{device_label}] audio too short; ignored", flush=True)
            return

        self.service.diag("asr_request", label=device_label, duration=round(duration, 3), captureRate=capture_rate, asrRate=self.args.sample_rate, wavBytes=len(wav_data))
        text = await recognize_full_audio(self.service, wav_data)

        if not text:
            self.service.diag("asr_result", ok=False, reason="no_text", label=device_label)
            print(f"[ASR:{device_label}] no valid text recognized", flush=True)
            return

        self.service.diag("asr_result", ok=True, label=device_label, text=text)

        if is_weak_voice_text(text):
            self.service.diag("asr_skip", reason="weak_text", label=device_label, text=text)
            print(f"[ASR:{device_label}] weak input ignored: {text}", flush=True)
            return

        if not self.service.recently_focused():
            self.service.diag("asr_skip", reason="focus_gate", label=device_label, text=text, focusGate=self.service.focus_gate_status)
            print(f"[ASR:{device_label}] focus gate closed; ignored: {text}", flush=True)
            return

        if not self.service.should_forward_text(text):
            self.service.diag("asr_skip", reason="duplicate", label=device_label, text=text)
            return

        print(f"[ASR:{device_label}] recognized: {text}", flush=True)
        labeled = f"[Voice from microphone]: {text}"
        self.service.send_to_mindcraft(labeled)


# ==========================================
# HTTP 控制端点 (mute/unmute for echo suppression)
# ==========================================

from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

_asr_service_ref = None  # set in main()


class ASRControlHandler(BaseHTTPRequestHandler):
    """Tiny HTTP server for echo suppression, push-to-talk, and diagnostics."""

    def log_message(self, format, *args):
        pass  # suppress default logging

    def do_POST(self):
        global _asr_service_ref
        try:
            length = int(self.headers.get("Content-Length", "0") or 0)
        except ValueError:
            length = 0
        if length > 0:
            self.rfile.read(length)
        if self.path == "/mute":
            if _asr_service_ref:
                _asr_service_ref.muted = True
                print("🔇 [ASR] muted by voice server", flush=True)
            self._respond(200, {"muted": True})
        elif self.path == "/unmute":
            if _asr_service_ref:
                _asr_service_ref.muted = False
                print("🔊 [ASR] unmuted by voice server", flush=True)
            self._respond(200, {"muted": False})
        elif self.path == "/ptt/start":
            self._respond(200, _asr_service_ref.ptt_start() if _asr_service_ref else {"ok": False, "error": "asr_not_ready"})
        elif self.path == "/ptt/stop":
            self._respond(200, _asr_service_ref.ptt_stop() if _asr_service_ref else {"ok": False, "error": "asr_not_ready"})
        else:
            self._respond(404, {"error": "not found"})

    def do_GET(self):
        global _asr_service_ref
        if self.path == "/status":
            self._respond(200, _asr_service_ref.status_payload() if _asr_service_ref else {"ok": False, "error": "asr_not_ready"})
        elif self.path == "/diag":
            self._respond(200, _asr_service_ref.diag_payload() if _asr_service_ref else {"ok": False, "error": "asr_not_ready"})
        else:
            self._respond(404, {"error": "not found"})

    def _respond(self, code, body):
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.wfile.flush()
        self.close_connection = True


def start_control_server(port=8089):
    """Start the mute/unmute HTTP control server in a daemon thread."""
    server = ThreadingHTTPServer(("127.0.0.1", port), ASRControlHandler)
    server.daemon_threads = True
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    print(f"🎛️ [ASR] 控制端点已启动: http://127.0.0.1:{port} (POST /mute, /unmute, GET /status)", flush=True)
    return server


# ==========================================
# 主入口
# ==========================================

def main():
    parser = argparse.ArgumentParser(description="Syna ASR — 整句麦克风语音识别，自动转发到 Mindcraft")
    parser.add_argument("--mindcraft-url", default=os.getenv("MINDCRAFT_URL", "http://127.0.0.1:8081"),
                        help="Mindcraft HTTP 地址 (默认 http://127.0.0.1:8081)")
    parser.add_argument("--default-agent", default=os.getenv("MINDCRAFT_AGENT", ""),
                        help="目标 bot 名称（不填则从 settings.js 自动读取）")
    parser.add_argument("--sender-name", default="SynaMic",
                        help="发送者名称 (默认 SynaMic)")
    parser.add_argument("--sample-rate", type=int, default=16000,
                        help="麦克风采样率 (默认 16000)")
    parser.add_argument("--chunk-size", type=int, default=1024,
                        help="麦克风每帧大小 (默认 1024)")
    parser.add_argument("--neuro-capture", action="store_true",
                        help="Use the stable NeuroSama path: default input, mono PCM16, native 16kHz capture.")
    parser.add_argument("--end-silence-frames", type=int, default=16,
                        help="静音多少帧后判定说完 (默认 16，约1.0秒)")
    parser.add_argument("--max-recording-seconds", type=float, default=8.0,
                        help="Maximum seconds for one utterance before forcing recognition. Default 8 prevents stuck recording.")
    parser.add_argument("--input-device", type=int, default=None,
                        help="PyAudio input device index. Use --list-devices to see available microphones.")
    parser.add_argument("--all-input-devices", action="store_true",
                        help="Listen using smart-selected input endpoints. Ignored when --input-device is set.")
    parser.add_argument("--smart-input-devices", action="store_true", default=True,
                        help="Deduplicate PortAudio endpoints before listening. Enabled by default.")
    parser.add_argument("--no-smart-input-devices", dest="smart_input_devices", action="store_false",
                        help="Debug only: literally open every input endpoint.")
    parser.add_argument("--max-active-input-devices", type=int, default=1,
                        help="Maximum selected endpoints to open in --all-input-devices mode. Default 1 avoids duplicate backend conflicts.")
    parser.add_argument("--preferred-input-name", default=os.getenv("SYNA_PREFERRED_INPUT_NAME", ""),
                        help="Prefer input devices whose name contains this text.")
    parser.add_argument("--list-devices", action="store_true",
                        help="List available PyAudio input devices and exit.")
    parser.add_argument("--dedupe-seconds", type=float, default=4.0,
                        help="Ignore identical recognized text within this many seconds across microphones.")
    parser.add_argument("--device-rescan-seconds", type=float, default=10.0,
                        help="In --all-input-devices mode, rescan for newly available microphones this often.")
    parser.add_argument("--diag-dir", default="",
                        help="Directory for ASR diagnostic jsonl logs. Default: ./logs")
    parser.add_argument("--diag-events", type=int, default=400,
                        help="Number of recent diagnostic events exposed by GET /diag")
    parser.add_argument("--rms-threshold", type=float, default=800,
                        help="RMS 音量阈值，超过则认为在说话 (默认 800)")
    parser.add_argument("--volc-app-id", default="",
                        help="火山 APP ID (也可在 keys.json 中配置)")
    parser.add_argument("--volc-access-token", default="",
                        help="火山 Access Token (也可在 keys.json 中配置)")
    parser.add_argument("--voice-base-url", default=os.getenv("SYNA_VOICE_BASE_URL", "http://127.0.0.1:8766"),
                        help="Syna voice service base URL for realtime TTS interruption")
    parser.add_argument("--mod-bridge-url", default=os.getenv("SYNA_MOD_BRIDGE_URL", "http://127.0.0.1:8765"),
                        help="SynaBridge mod HTTP URL for V-key focus gating")
    parser.add_argument("--focus-window-seconds", type=float, default=8.0,
                        help="Only forward ASR text if V/focus was pressed within this many seconds when --require-focus is enabled")
    parser.add_argument("--require-focus", action="store_true",
                        help="Require recent V/focus before forwarding recognized speech. Default is always listening.")
    parser.add_argument("--always-listen", action="store_true",
                        help="Compatibility no-op: ASR now always listens unless --require-focus is set.")
    parser.add_argument("--push-to-talk", action="store_true",
                        help="Only capture audio while the Minecraft V key is held; release submits immediately.")
    parser.add_argument("--pure-mod", action="store_true",
                        help="Forward recognized text directly to SynaBridge /input instead of MindServer.")
    parser.add_argument("--volc-asr-resource-id", default="",
                        help="火山 ASR Resource ID (默认 volc.seedasr.sauc.duration)")
    args = parser.parse_args()

    if args.neuro_capture:
        args.input_device = None
        args.all_input_devices = False
        args.sample_rate = 16000
        args.chunk_size = 512

    if args.list_devices:
        raise SystemExit(print_input_devices())

    if pyaudio is None or websockets is None:
        print("❌ [SynaASR] 缺少必要依赖，请安装：pip install pyaudio websockets requests", flush=True)
        return

    service = SynaASRService(args)

    # Start mute/unmute control endpoint & set global ref
    global _asr_service_ref
    _asr_service_ref = service
    start_control_server(port=8089)

    print("=" * 60, flush=True)
    print("🎤 Syna 整句 ASR 语音识别服务 for Mindcraft", flush=True)
    print("=" * 60, flush=True)
    print(f"   Mindcraft: {args.mindcraft_url}", flush=True)
    input_mode = args.input_device if args.input_device is not None else ("all" if args.all_input_devices else "default")
    print(f"   Input device: {input_mode}", flush=True)
    print(f"   Agent: {service.default_agent or '(自动检测)'}", flush=True)
    print(f"   发送者: {args.sender_name}", flush=True)
    print(f"   火山 APP: {service.app_id[:8]}..." if service.app_id else "   火山 APP: (未配置)", flush=True)
    print(f"   模式: 整句（录完再识别，稳定可靠）", flush=True)
    end_silence_seconds = args.end_silence_frames * args.chunk_size / args.sample_rate
    print(f"   静音判定: {end_silence_seconds:.1f}s ({args.end_silence_frames}帧)", flush=True)
    if service.keys_path:
        print(f"   keys.json: {service.keys_path}", flush=True)
    print("=" * 60, flush=True)
    print("对着麦克风说话，说完后自动识别并发送给 MC bot。Ctrl+C 退出。", flush=True)
    print("", flush=True)

    # 创建事件循环
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    # 启动麦克风录音管理器
    mic_manager = MicRecordManager(service, loop)
    def run_microphone():
        try:
            mic_manager.run()
        except BaseException as e:
            service.diag("microphone_thread_crashed", error=str(e), errorType=type(e).__name__)
            print(f"[SynaASR] microphone thread crashed: {e}", flush=True)

    mic_thread = threading.Thread(target=run_microphone, daemon=True, name="syna-asr-microphone-manager")
    mic_thread.start()

    def watchdog():
        started_at = time.time()
        while service.is_running:
            time.sleep(2.0)
            if time.time() - started_at < 12.0:
                continue
            status = service.status_payload()
            stale = status.get("staleSeconds")
            unhealthy = not bool(status.get("healthy")) and (stale is None or float(stale) >= 6.0)
            if mic_thread.is_alive() and not unhealthy:
                continue
            reason = "thread_exited" if not mic_thread.is_alive() else f"audio_stale:{stale}"
            service.diag("watchdog_restart", reason=reason)
            print(f"[SynaASR] watchdog restarting stale service ({reason})", flush=True)
            try:
                os.execv(sys.executable, [sys.executable] + sys.argv)
            except Exception as e:
                service.diag("watchdog_restart_failed", error=str(e), errorType=type(e).__name__)
                os._exit(75)

    threading.Thread(target=watchdog, daemon=True, name="syna-asr-watchdog").start()

    # 运行事件循环
    try:
        loop.run_forever()
    except KeyboardInterrupt:
        print("\n👋 [SynaASR] 已退出", flush=True)
        service.is_running = False


if __name__ == "__main__":
    main()
