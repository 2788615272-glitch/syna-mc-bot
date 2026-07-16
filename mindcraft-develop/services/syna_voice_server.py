import argparse
import base64
import io
import json
import os
from pathlib import Path
import queue
import socket
import re
import threading
import time
import uuid
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

try:
    import requests
except Exception:
    requests = None

try:
    import pyaudio
except Exception:
    pyaudio = None


def compact(text, limit=80):
    text = re.sub(r"\s+", " ", str(text or "")).strip()
    return text[:limit] + ("..." if len(text) > limit else "")


def log(*args, **kwargs):
    kwargs.setdefault("flush", True)
    try:
        print(*args, **kwargs)
    except (OSError, ValueError):
        pass


def sanitize_tts_text(text):
    text = str(text or "")
    text = re.sub(r"</?s>", "", text, flags=re.IGNORECASE)
    text = re.sub(r"<\|[^|>]+\|>", "", text)
    text = re.sub(r"</?(?:assistant|user|system)>", "", text, flags=re.IGNORECASE)
    text = re.sub(r"```[\s\S]*?```", " ", text)
    text = re.sub(r"!\w+\s*\([^\n]*\)", " ", text)
    text = re.sub(r"!\w+", " ", text)
    text = re.sub(r"[*_`>#-]+", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:220]


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
            log(f"[SynaVoice] failed to read keys.json {path}: {e}", flush=True)
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


class SynaVoiceService:
    def __init__(self, args):
        self.args = args
        self.keys_config, self.keys_path = load_keys_config()
        self.queue = queue.Queue(maxsize=args.queue_size)
        self.stop_audio = threading.Event()
        self.generation = 0
        self.generation_lock = threading.Lock()
        self.current_burst_id = ""
        self.next_broadcast_at = 0.0
        self.default_agent = args.default_agent or load_default_agent_name()
        self.audio_dir = Path(args.audio_dir).resolve()
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        self.worker = threading.Thread(target=self._worker_loop, daemon=True)
        self.worker.start()

    def enqueue(self, text, interrupt=False, speed=None, burst_id="", sequence=0):
        text = sanitize_tts_text(text)
        if not text:
            return {"ok": False, "reason": "empty text"}
        burst_id = str(burst_id or "")
        try:
            sequence = int(sequence or 0)
        except Exception:
            sequence = 0
        if interrupt and burst_id and burst_id == self.current_burst_id:
            interrupt = False
        if interrupt:
            self.interrupt()
        if burst_id:
            self.current_burst_id = burst_id
        with self.generation_lock:
            generation = self.generation
        item = {"text": text, "speed": self._normalize_speed(speed), "generation": generation, "burst_id": burst_id, "sequence": sequence}
        try:
            self.queue.put_nowait(item)
        except queue.Full:
            try:
                _ = self.queue.get_nowait()
                self.queue.task_done()
            except Exception:
                pass
            self.queue.put_nowait(item)
        speed_label = "default" if item.get("speed") is None else item.get("speed")
        burst_label = burst_id or "none"
        log(f"[SynaVoice] queued: {compact(text)} speed={speed_label} burst={burst_label} seq={sequence}", flush=True)
        return {"ok": True, "queued": self.queue.qsize(), "speed": item.get("speed"), "burstId": burst_id, "sequence": sequence}

    def interrupt(self):
        self.stop_audio.set()
        with self.generation_lock:
            self.generation += 1
            generation = self.generation
        while True:
            try:
                _ = self.queue.get_nowait()
                self.queue.task_done()
            except queue.Empty:
                break
        self.current_burst_id = ""
        self.next_broadcast_at = 0.0
        self._broadcast_interrupt_to_synabridge(generation)
        log("[SynaVoice] interrupted current audio and cleared queue", flush=True)

    def health_payload(self):
        public_host = self.args.public_host or self._guess_public_host()
        return {
            "ok": True,
            "ready": True,
            "service": "syna_voice",
            "queue": self.queue.qsize(),
            "host": self.args.host,
            "port": self.args.port,
            "public_host": public_host,
            "public_audio_base": f"http://{public_host}:{self.args.port}/audio/",
            "broadcast_to_mod": bool(self.args.broadcast_to_mod),
            "local_playback": bool(self.args.local_playback),
            "mindcraft_url": self.args.mindcraft_url,
            "default_agent": self.default_agent,
        }

    def send_text_to_mindcraft(self, text, agent_name="", sender="Syna", say=False, interrupt=False):
        if requests is None:
            return {"ok": False, "error": "requests not installed"}
        text = str(text or "").strip()
        if not text:
            return {"ok": False, "error": "empty text"}
        agent_name = (agent_name or self.default_agent or "").strip()
        if not agent_name:
            return {"ok": False, "error": "agent_name is required (or configure --default-agent)"}

        base_url = self.args.mindcraft_url.rstrip("/")
        url = f"{base_url}/api/agents/{agent_name}/message"
        payload = {
            "from": sender or "Syna",
            "message": text,
        }
        log(f"[Syna->Mindcraft] agent={agent_name} from={sender or 'Syna'} text={compact(text)}", flush=True)
        resp = requests.post(url, json=payload, timeout=self.args.mindcraft_timeout)
        resp.raise_for_status()
        result = resp.json()
        if say:
            self.enqueue(text, interrupt=interrupt)
        return result

    def send_voice_input_to_mindcraft(self, text, agent_name="", sender="SynaMic", say=False, interrupt=False):
        if requests is None:
            return {"ok": False, "error": "requests not installed"}
        text = str(text or "").strip()
        if not text:
            return {"ok": False, "error": "empty text"}
        agent_name = (agent_name or self.default_agent or "").strip()
        if not agent_name:
            return {"ok": False, "error": "agent_name is required (or configure --default-agent)"}

        base_url = self.args.mindcraft_url.rstrip("/")
        url = f"{base_url}/api/agents/{agent_name}/voice-input"
        payload = {
            "from": sender or "SynaMic",
            "text": text,
        }
        log(f"[VoiceInput->Mindcraft] agent={agent_name} from={sender or 'SynaMic'} text={compact(text)}", flush=True)
        resp = requests.post(url, json=payload, timeout=self.args.mindcraft_timeout)
        resp.raise_for_status()
        result = resp.json()
        if say:
            self.enqueue(text, interrupt=interrupt)
        return result

    def _worker_loop(self):
        while True:
            item = self.queue.get()
            try:
                if isinstance(item, dict):
                    text = item.get("text", "")
                    speed = item.get("speed")
                    generation = int(item.get("generation", 0) or 0)
                else:
                    text = str(item or "")
                    speed = None
                    generation = self.generation
                audio = self._synthesize_volc(text, speed)
                if audio and generation == self.generation:
                    self._wait_for_previous_broadcast(generation)
                    if generation != self.generation:
                        continue
                    audio_url = self._publish_audio(audio, text)
                    self._broadcast_to_synabridge(audio_url, text, audio, generation)
                    self._reserve_broadcast_window(audio)
                    if self.args.local_playback:
                        self._play_wav(audio)
            except Exception as e:
                log(f"[SynaVoice] synth/play failed: {e}", flush=True)
            finally:
                self.queue.task_done()

    def _audio_duration_seconds(self, audio_bytes):
        try:
            with wave.open(io.BytesIO(audio_bytes), "rb") as wf:
                rate = wf.getframerate() or 1
                return max(0.15, wf.getnframes() / float(rate))
        except Exception:
            return 0.8

    def _wait_for_previous_broadcast(self, generation):
        delay = self.next_broadcast_at - time.time()
        while delay > 0 and generation == self.generation:
            time.sleep(min(delay, 0.1))
            delay = self.next_broadcast_at - time.time()

    def _reserve_broadcast_window(self, audio_bytes):
        duration = self._audio_duration_seconds(audio_bytes)
        self.next_broadcast_at = max(self.next_broadcast_at, time.time()) + min(max(duration + 0.08, 0.3), 20.0)

    def _normalize_speed(self, speed):
        try:
            if speed is None or speed == "":
                return None
            value = float(speed)
            return max(0.55, min(1.8, value))
        except Exception:
            return None

    def _synthesize_volc(self, text, speed_override=None):
        if requests is None:
            log("[SynaVoice] requests is missing; install requests to use Volc TTS", flush=True)
            return None

        cfg = self.keys_config or {}
        app_id = self.args.volc_app_id or os.getenv("VOLC_APP_ID") or cfg.get("VOLC_APP_ID")
        access_token = self.args.volc_access_token or os.getenv("VOLC_ACCESS_TOKEN") or cfg.get("VOLC_ACCESS_TOKEN")
        voice_id = self.args.volc_voice_id or os.getenv("VOLC_VOICE_ID") or cfg.get("VOLC_VOICE_ID")
        cluster = cfg.get("VOLC_CLUSTER") or self.args.volc_cluster or "volcano_icl"
        speed = speed_override if speed_override is not None else cfg.get("VOLC_SPEED", self.args.speed if self.args.speed is not None else 1.0)
        if not app_id or not access_token or not voice_id:
            suffix = f" keys={self.keys_path}" if self.keys_path else ""
            log(f"[SynaVoice] missing VOLC_APP_ID / VOLC_ACCESS_TOKEN / VOLC_VOICE_ID, skip TTS.{suffix}", flush=True)
            return None

        url = "https://openspeech.bytedance.com/api/v1/tts"
        payload = {
            "app": {"appid": app_id, "token": access_token, "cluster": cluster},
            "user": {"uid": "mindcraft_syna"},
            "audio": {"voice_type": voice_id, "encoding": "wav", "speed_ratio": speed},
            "request": {"reqid": str(uuid.uuid4()), "text": text, "text_type": "plain", "operation": "query"}
        }
        log(f"[SynaVoice] synthesizing: {compact(text, 48)} speed={speed}", flush=True)
        resp = requests.post(url, json=payload, headers={"Authorization": f"Bearer;{access_token}"}, timeout=self.args.tts_timeout)
        resp.raise_for_status()
        data = resp.json()
        if data.get("code") != 3000:
            raise RuntimeError(f"Volc TTS code={data.get('code')} message={data.get('message')}")
        return base64.b64decode(data.get("data"))

    def _publish_audio(self, audio_bytes, text):
        voice_id = f"syna-{int(time.time() * 1000)}-{uuid.uuid4().hex[:8]}"
        path = self.audio_dir / f"{voice_id}.wav"
        path.write_bytes(audio_bytes)
        self._cleanup_audio_cache()
        host = self.args.public_host or self._guess_public_host()
        return f"http://{host}:{self.args.port}/audio/{path.name}"

    def _guess_public_host(self):
        if self.args.host not in ("0.0.0.0", "::", "127.0.0.1", "localhost"):
            return self.args.host
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.connect(("8.8.8.8", 80))
                host = sock.getsockname()[0]
                if host and not host.startswith("127."):
                    return host
        except Exception:
            pass
        try:
            host = socket.gethostbyname(socket.gethostname())
            if host and not host.startswith("127."):
                return host
        except Exception:
            pass
        return "127.0.0.1"
    def _cleanup_audio_cache(self):
        cutoff = time.time() - float(self.args.audio_ttl_seconds)
        try:
            for path in self.audio_dir.glob("*.wav"):
                if path.stat().st_mtime < cutoff:
                    path.unlink(missing_ok=True)
        except Exception as e:
            log(f"[SynaVoice] audio cache cleanup failed: {e}", flush=True)

    def _broadcast_to_synabridge(self, audio_url, text, audio_bytes=None, generation=0):
        if not self.args.broadcast_to_mod:
            return
        if requests is None:
            return
        bridge_url = self.args.synabridge_url.rstrip("/") + "/voice/broadcast"
        payload = {
            "id": audio_url.rsplit("/", 1)[-1].replace(".wav", ""),
            "speaker": self.args.speaker_name or "Syna",
            "text": text,
            "url": audio_url,
            "generation": generation,
        }
        if audio_bytes and len(audio_bytes) <= int(self.args.mod_audio_inline_max_bytes):
            payload["audio_b64"] = base64.b64encode(audio_bytes).decode("ascii")
        try:
            resp = requests.post(bridge_url, json=payload, timeout=1.5)
            resp.raise_for_status()
            log(f"[SynaVoice] broadcast to SynaBridge: {audio_url}", flush=True)
        except Exception as e:
            log(f"[SynaVoice] broadcast to SynaBridge failed: {e}", flush=True)

    def _broadcast_interrupt_to_synabridge(self, generation):
        if not self.args.broadcast_to_mod or requests is None:
            return
        bridge_url = self.args.synabridge_url.rstrip("/") + "/voice/broadcast"
        payload = {
            "id": f"interrupt-{generation}",
            "speaker": self.args.speaker_name or "Syna",
            "text": "",
            "url": "",
            "interrupt": True,
            "generation": generation,
        }
        try:
            requests.post(bridge_url, json=payload, timeout=0.8)
        except Exception:
            pass
    def _mute_asr(self):
        """Disabled: headphone mode 鈥?no echo suppression needed."""
        return
        try:
            requests.post("http://127.0.0.1:8089/mute", timeout=1.0)
        except Exception:
            pass

    def _unmute_asr(self):
        """Disabled: headphone mode 鈥?no echo suppression needed."""
        return
        try:
            requests.post("http://127.0.0.1:8089/unmute", timeout=1.0)
        except Exception:
            pass

    def _play_wav(self, audio_bytes):
        if pyaudio is None:
            log("[SynaVoice] pyaudio is missing; local Python playback disabled", flush=True)
            return
        self.stop_audio.clear()
        self._mute_asr()
        with wave.open(io.BytesIO(audio_bytes), "rb") as wf:
            pa = pyaudio.PyAudio()
            # Use a larger chunk (~100ms worth of audio) so we check stop_audio frequently
            sample_rate = wf.getframerate()
            channels = wf.getnchannels()
            sample_width = wf.getsampwidth()
            # ~100ms of audio per chunk for responsive interruption
            chunk = max(1024, int(sample_rate * channels * 0.1))
            stream = pa.open(
                format=pa.get_format_from_width(sample_width),
                channels=channels,
                rate=sample_rate,
                output=True,
                frames_per_buffer=chunk,
            )
            try:
                data = wf.readframes(chunk)
                while data:
                    # Check interrupt before writing for responsive cancellation.
                    if self.stop_audio.is_set():
                        log("[SynaVoice] playback interrupted", flush=True)
                        break
                    stream.write(data)
                    data = wf.readframes(chunk)
            finally:
                stream.stop_stream()
                stream.close()
                pa.terminate()
                self._unmute_asr()


def make_handler(service):
    class Handler(BaseHTTPRequestHandler):
        def _json(self, status, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            parsed = urlparse(self.path)
            if parsed.path == "/health" or parsed.path == "/ready":
                self._json(200, service.health_payload())
            elif parsed.path.startswith("/audio/"):
                self._audio(parsed.path.rsplit("/", 1)[-1])
            else:
                self._json(404, {"ok": False, "error": "not found"})

        def _audio(self, name):
            safe = os.path.basename(name or "")
            path = service.audio_dir / safe
            if not safe.endswith(".wav") or not path.exists():
                self._json(404, {"ok": False, "error": "audio_not_found"})
                return
            data = path.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            try:
                length = int(self.headers.get("Content-Length", "0") or 0)
                data = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
                if self.path == "/say":
                    self._json(200, service.enqueue(
                        data.get("text", ""),
                        bool(data.get("interrupt")),
                        data.get("speed"),
                        data.get("burstId", data.get("burst_id", "")),
                        data.get("sequence", 0),
                    ))
                elif self.path == "/send-text":
                    self._json(200, service.send_text_to_mindcraft(
                        data.get("text", ""),
                        data.get("agent_name", ""),
                        data.get("from", "Syna"),
                        bool(data.get("say")),
                        bool(data.get("interrupt")),
                    ))
                elif self.path == "/voice-input":
                    self._json(200, service.send_voice_input_to_mindcraft(
                        data.get("text", ""),
                        data.get("agent_name", ""),
                        data.get("from", "SynaMic"),
                        bool(data.get("say")),
                        bool(data.get("interrupt")),
                    ))
                elif self.path == "/interrupt":
                    service.interrupt()
                    self._json(200, {"ok": True})
                else:
                    self._json(404, {"ok": False, "error": "not found"})
            except Exception as e:
                self._json(500, {"ok": False, "error": str(e)})

        def log_message(self, fmt, *args):
            return

    return Handler


def main():
    parser = argparse.ArgumentParser(description="Local Syna voice bridge for Mindcraft")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--queue-size", type=int, default=8)
    parser.add_argument("--volc-app-id", default="")
    parser.add_argument("--volc-access-token", default="")
    parser.add_argument("--volc-voice-id", default="")
    parser.add_argument("--volc-cluster", default="")
    parser.add_argument("--speed", type=float, default=1.0)
    parser.add_argument("--tts-timeout", type=float, default=20.0)
    parser.add_argument("--mindcraft-url", default=os.getenv("MINDCRAFT_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--mindcraft-timeout", type=float, default=5.0)
    parser.add_argument("--default-agent", default=os.getenv("MINDCRAFT_AGENT", ""))
    parser.add_argument("--synabridge-url", default=os.getenv("SYNABRIDGE_URL", "http://127.0.0.1:8765"))
    parser.add_argument("--broadcast-to-mod", action="store_true", default=True)
    parser.add_argument("--no-broadcast-to-mod", dest="broadcast_to_mod", action="store_false")
    parser.add_argument("--public-host", default=os.getenv("SYNA_VOICE_PUBLIC_HOST", ""))
    parser.add_argument("--speaker-name", default="Syna")
    parser.add_argument("--local-playback", action="store_true", default=False)
    parser.add_argument("--no-local-playback", dest="local_playback", action="store_false")
    parser.add_argument("--audio-dir", default=str(Path(__file__).resolve().parent.parent / "logs" / "syna_voice_audio"))
    parser.add_argument("--audio-ttl-seconds", type=float, default=300.0)
    parser.add_argument("--mod-audio-inline-max-bytes", type=int, default=768000)
    args = parser.parse_args()

    service = SynaVoiceService(args)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(service))
    log(f"[SynaVoice] server started: http://{args.host}:{args.port}", flush=True)
    public_host = args.public_host or service._guess_public_host()
    log(f"   Mod audio URL base: http://{public_host}:{args.port}/audio/...", flush=True)
    log(f"   Playback: mod_broadcast={'on' if args.broadcast_to_mod else 'off'}, local_python={'on' if args.local_playback else 'off'}", flush=True)
    log("   Health: GET /health or /ready; speak: POST /say; input: POST /voice-input", flush=True)
    log(f"   Mindcraft: {args.mindcraft_url} default_agent={service.default_agent or '(not set)'}", flush=True)
    if service.keys_path:
        log(f"   keys.json: {service.keys_path}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log("\n[SynaVoice] stopped", flush=True)


if __name__ == "__main__":
    main()

