using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace SynaLauncher
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new LauncherForm());
        }
    }

    public sealed class LauncherForm : Form
    {
        private readonly string appDir;
        private readonly string nodePath;
        private readonly int mindserverPort;
        private Process mindcraftProcess;
        private TextBox hostInput;
        private TextBox portInput;
        private TextBox versionInput;
        private Label statusLabel;
        private Label configLabel;
        private Label modelLabel;
        private Button saveButton;
        private Button testButton;
        private Button startButton;
        private Button stopButton;
        private Button openButton;
        private TextBox logBox;
        private Timer statusTimer;
        private bool connectionReady;

        public LauncherForm()
        {
            appDir = FindAppDir();
            nodePath = FindNodePath(appDir);
            mindserverPort = ReadMindserverPort(appDir);
            BuildUi();
            LoadControlConfig();
            RefreshServerStatusAsync();

            statusTimer = new Timer();
            statusTimer.Interval = 2500;
            statusTimer.Tick += async (s, e) => await RefreshServerStatusAsync();
            statusTimer.Start();
        }

        private string ConfigPath
        {
            get { return Path.Combine(appDir, "control_config.json"); }
        }

        private string FindAppDir()
        {
            string dir = AppDomain.CurrentDomain.BaseDirectory;
            for (int i = 0; i < 6 && dir != null; i++)
            {
                if (File.Exists(Path.Combine(dir, "main.js")) && Directory.Exists(Path.Combine(dir, "src")))
                    return dir;
                DirectoryInfo parent = Directory.GetParent(dir);
                dir = parent == null ? null : parent.FullName;
            }
            return Directory.GetCurrentDirectory();
        }

        private string FindNodePath(string root)
        {
            string bundled = Path.GetFullPath(Path.Combine(root, "..", ".node20", "node.exe"));
            if (File.Exists(bundled)) return bundled;
            return "node.exe";
        }

        private static int ReadMindserverPort(string root)
        {
            try
            {
                string settingsPath = Path.Combine(root, "settings.js");
                if (!File.Exists(settingsPath)) return 8081;
                string settings = File.ReadAllText(settingsPath, Encoding.UTF8);
                string value = MatchString(settings, "\\\"mindserver_port\\\"\\s*:\\s*(\\d+)", "8081");
                int port;
                return int.TryParse(value, out port) && port > 0 && port < 65536 ? port : 8081;
            }
            catch
            {
                return 8081;
            }
        }

        private void BuildUi()
        {
            Text = "Syna \u542f\u52a8\u5668";
            Width = 920;
            Height = 640;
            MinimumSize = new Size(820, 560);
            BackColor = Color.White;
            Font = new Font("Microsoft YaHei UI", 9F);

            var root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.Padding = new Padding(22);
            root.BackColor = Color.White;
            root.ColumnCount = 1;
            root.RowCount = 4;
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 66));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 198));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 70));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            Controls.Add(root);

            var header = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2 };
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 60));
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 40));

            var title = new Label
            {
                Text = "Syna Minecraft Bot",
                Dock = DockStyle.Fill,
                Font = new Font("Microsoft YaHei UI", 20F, FontStyle.Bold),
                ForeColor = Color.FromArgb(30, 36, 43),
                TextAlign = ContentAlignment.MiddleLeft
            };
            statusLabel = new Label
            {
                Text = "\u672a\u542f\u52a8",
                Dock = DockStyle.Fill,
                ForeColor = Color.FromArgb(95, 105, 115),
                TextAlign = ContentAlignment.MiddleRight
            };
            header.Controls.Add(title, 0, 0);
            header.Controls.Add(statusLabel, 1, 0);
            root.Controls.Add(header, 0, 0);

            var cards = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2 };
            cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 55));
            cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 45));
            root.Controls.Add(cards, 0, 1);

            var mcPanel = CreatePanel("Minecraft \u8fde\u63a5");
            var mcGrid = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 5 };
            mcGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 96));
            mcGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            mcPanel.Controls.Add(mcGrid);

            hostInput = AddInputRow(mcGrid, 0, "\u5730\u5740", "127.0.0.1");
            portInput = AddInputRow(mcGrid, 1, "\u5c40\u57df\u7f51\u7aef\u53e3", "");
            versionInput = AddInputRow(mcGrid, 2, "\u7248\u672c", "auto");
            configLabel = new Label
            {
                Text = "\u7aef\u53e3\u4e3a\u7a7a\u65f6\u4e0d\u80fd\u542f\u52a8\u3002",
                Dock = DockStyle.Fill,
                ForeColor = Color.FromArgb(95, 105, 115),
                TextAlign = ContentAlignment.MiddleLeft
            };
            mcGrid.Controls.Add(configLabel, 0, 3);
            mcGrid.SetColumnSpan(configLabel, 2);

            var mcButtons = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
            saveButton = CreateButton("\u4fdd\u5b58", Color.FromArgb(236, 239, 242), Color.FromArgb(30, 36, 43));
            testButton = CreateButton("\u6d4b\u8bd5\u8fde\u63a5", Color.FromArgb(31, 120, 255), Color.White);
            saveButton.Click += async (s, e) => await SaveConfigAsync(false);
            testButton.Click += async (s, e) => await TestConnectionAsync();
            mcButtons.Controls.Add(saveButton);
            mcButtons.Controls.Add(testButton);
            mcGrid.Controls.Add(mcButtons, 0, 4);
            mcGrid.SetColumnSpan(mcButtons, 2);
            cards.Controls.Add(mcPanel, 0, 0);

            var modelPanel = CreatePanel("\u5f53\u524d\u6a21\u578b");
            modelLabel = new Label
            {
                Dock = DockStyle.Fill,
                Text = "\u8bfb\u53d6\u4e2d...",
                ForeColor = Color.FromArgb(56, 64, 73),
                Padding = new Padding(4, 8, 4, 4)
            };
            modelPanel.Controls.Add(modelLabel);
            cards.Controls.Add(modelPanel, 1, 0);

            var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
            startButton = CreateButton("\u542f\u52a8 Syna", Color.FromArgb(31, 120, 255), Color.White);
            stopButton = CreateButton("\u505c\u6b62", Color.FromArgb(230, 72, 72), Color.White);
            openButton = CreateButton("\u6253\u5f00\u63a7\u5236\u53f0", Color.FromArgb(236, 239, 242), Color.FromArgb(30, 36, 43));
            startButton.Click += async (s, e) => await StartMindcraftAsync();
            stopButton.Click += (s, e) => StopMindcraft();
            openButton.Click += (s, e) => OpenConsole();
            stopButton.Enabled = false;
            actions.Controls.Add(startButton);
            actions.Controls.Add(stopButton);
            actions.Controls.Add(openButton);
            root.Controls.Add(actions, 0, 2);

            logBox = new TextBox
            {
                Dock = DockStyle.Fill,
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                BorderStyle = BorderStyle.FixedSingle,
                BackColor = Color.FromArgb(248, 249, 250),
                ForeColor = Color.FromArgb(38, 45, 52),
                Font = new Font("Consolas", 9F)
            };
            root.Controls.Add(logBox, 0, 3);
        }

        private Panel CreatePanel(string title)
        {
            var wrapper = new Panel
            {
                Dock = DockStyle.Fill,
                BackColor = Color.White,
                Padding = new Padding(12),
                Margin = new Padding(0, 0, 14, 0)
            };
            wrapper.Paint += (s, e) =>
            {
                using (var pen = new Pen(Color.FromArgb(225, 229, 233)))
                    e.Graphics.DrawRectangle(pen, 0, 0, wrapper.Width - 1, wrapper.Height - 1);
            };
            var label = new Label
            {
                Text = title,
                Dock = DockStyle.Top,
                Height = 30,
                Font = new Font("Microsoft YaHei UI", 11F, FontStyle.Bold),
                ForeColor = Color.FromArgb(30, 36, 43)
            };
            wrapper.Controls.Add(label);
            return wrapper;
        }

        private TextBox AddInputRow(TableLayoutPanel grid, int row, string label, string initialValue)
        {
            var caption = new Label { Text = label, Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
            var input = new TextBox { Dock = DockStyle.Fill, BorderStyle = BorderStyle.FixedSingle };
            input.Text = initialValue;
            input.TextChanged += (s, e) => SetConnectionReady(false);
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
            grid.Controls.Add(caption, 0, row);
            grid.Controls.Add(input, 1, row);
            return input;
        }

        private Button CreateButton(string text, Color back, Color fore)
        {
            var button = new Button
            {
                Text = text,
                Width = 118,
                Height = 36,
                FlatStyle = FlatStyle.Flat,
                BackColor = back,
                ForeColor = fore,
                Margin = new Padding(0, 8, 10, 0)
            };
            button.FlatAppearance.BorderColor = Color.FromArgb(210, 216, 222);
            return button;
        }

        private void LoadControlConfig()
        {
            try
            {
                string json = File.Exists(ConfigPath) ? File.ReadAllText(ConfigPath, Encoding.UTF8) : "";
                hostInput.Text = MatchString(json, "\"host\"\\s*:\\s*\"([^\"]+)\"", "127.0.0.1");
                portInput.Text = MatchString(json, "\"port\"\\s*:\\s*(\\d+)", "");
                versionInput.Text = MatchString(json, "\"minecraft_version\"\\s*:\\s*\"([^\"]+)\"", "auto");
                modelLabel.Text = ReadModelSummary();
                SetConnectionReady(json.Contains("\"ok\": true"));
            }
            catch (Exception ex)
            {
                AppendLog("\u8bfb\u53d6\u914d\u7f6e\u5931\u8d25: " + ex.Message);
                SetConnectionReady(false);
            }
        }

        private static string MatchString(string text, string pattern, string fallback)
        {
            var match = Regex.Match(text ?? "", pattern);
            return match.Success ? match.Groups[1].Value : fallback;
        }

        private string ReadModelSummary()
        {
            string profile = Path.Combine(appDir, "profiles", "syna.json");
            if (!File.Exists(profile)) return "\u672a\u627e\u5230 profiles/syna.json";
            string json = File.ReadAllText(profile, Encoding.UTF8);
            string api = MatchString(json, "\"api\"\\s*:\\s*\"([^\"]+)\"", "\u672a\u77e5");
            string model = MatchString(json, "\"model\"\\s*:\\s*\"([^\"]+)\"", "\u672a\u77e5");
            return "\u4e3b\u6a21\u578b: " + api + " / " + model + Environment.NewLine +
                   "\u6a21\u578b\u8be6\u7ec6\u914d\u7f6e\u8bf7\u5728\u7f51\u9875\u63a7\u5236\u53f0\u4e2d\u8c03\u6574\u3002";
        }

        private Task SaveConfigAsync(bool keepTest)
        {
            string portRaw = portInput.Text.Trim();
            int port;
            if (portRaw.Length == 0 || !int.TryParse(portRaw, out port) || port < 1 || port > 65535)
            {
                SetConnectionReady(false);
                configLabel.Text = "\u8bf7\u5148\u586b\u5199 1-65535 \u7684\u5c40\u57df\u7f51\u7aef\u53e3\u3002";
                return Task.CompletedTask;
            }

            string host = hostInput.Text.Trim().Length == 0 ? "127.0.0.1" : hostInput.Text.Trim();
            string version = versionInput.Text.Trim().Length == 0 ? "auto" : versionInput.Text.Trim();
            string lastTest = keepTest && connectionReady
                ? ",\n        \"last_test\": { \"ok\": true, \"host\": \"" + Escape(host) + "\", \"port\": " + port + ", \"tested_at\": \"" + DateTime.UtcNow.ToString("o") + "\" }"
                : ",\n        \"last_test\": null";
            string json = "{\n  \"minecraft\": {\n" +
                "        \"host\": \"" + Escape(host) + "\",\n" +
                "        \"port\": " + port + ",\n" +
                "        \"minecraft_version\": \"" + Escape(version) + "\"" +
                lastTest + "\n  }\n}\n";
            File.WriteAllText(ConfigPath, json, Encoding.UTF8);
            AppendLog("\u5df2\u4fdd\u5b58 Minecraft \u8fde\u63a5\u914d\u7f6e\u3002");
            return Task.CompletedTask;
        }

        private static string Escape(string value)
        {
            return (value ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"");
        }

        private async Task TestConnectionAsync()
        {
            await SaveConfigAsync(false);
            if (portInput.Text.Trim().Length == 0) return;

            SetBusy(true);
            configLabel.Text = "\u6b63\u5728\u6d4b\u8bd5\u8fde\u63a5...";
            try
            {
                string script = Path.Combine(appDir, "launcher", "test_minecraft_connection.mjs");
                string host = hostInput.Text.Trim().Length == 0 ? "127.0.0.1" : hostInput.Text.Trim();
                string args = Quote(script) + " " + Quote(host) + " " + portInput.Text.Trim();
                string result = await RunNodeCaptureAsync(args, 5000);
                if (result.Contains("\"ok\":true"))
                {
                    SetConnectionReady(true);
                    await SaveConfigAsync(true);
                    configLabel.Text = "\u8fde\u63a5\u6210\u529f\uff0c\u53ef\u4ee5\u542f\u52a8\u3002";
                    AppendLog("Minecraft \u8fde\u63a5\u6d4b\u8bd5\u6210\u529f\u3002");
                }
                else
                {
                    SetConnectionReady(false);
                    configLabel.Text = ExtractError(result);
                    AppendLog("\u8fde\u63a5\u6d4b\u8bd5\u5931\u8d25: " + ExtractError(result));
                }
            }
            catch (Exception ex)
            {
                SetConnectionReady(false);
                configLabel.Text = "\u8fde\u63a5\u6d4b\u8bd5\u5931\u8d25: " + ex.Message;
                AppendLog(configLabel.Text);
            }
            finally
            {
                SetBusy(false);
            }
        }

        private async Task StartMindcraftAsync()
        {
            if (!connectionReady)
            {
                configLabel.Text = "\u8bf7\u5148\u6d4b\u8bd5 Minecraft \u8fde\u63a5\u3002";
                return;
            }
            if (mindcraftProcess != null && !mindcraftProcess.HasExited)
            {
                AppendLog("Mindcraft \u5df2\u7ecf\u5728\u8fd0\u884c\u3002");
                return;
            }

            await SaveConfigAsync(true);
            var psi = new ProcessStartInfo
            {
                FileName = nodePath,
                Arguments = "main.js",
                WorkingDirectory = appDir,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
            mindcraftProcess = new Process { StartInfo = psi, EnableRaisingEvents = true };
            mindcraftProcess.OutputDataReceived += (s, e) => { if (e.Data != null) AppendLog(e.Data); };
            mindcraftProcess.ErrorDataReceived += (s, e) => { if (e.Data != null) AppendLog(e.Data); };
            mindcraftProcess.Exited += (s, e) => BeginInvoke((Action)(() =>
            {
                statusLabel.Text = "\u5df2\u505c\u6b62";
                startButton.Enabled = true;
                stopButton.Enabled = false;
                AppendLog("Mindcraft \u5df2\u9000\u51fa\u3002");
            }));
            mindcraftProcess.Start();
            mindcraftProcess.BeginOutputReadLine();
            mindcraftProcess.BeginErrorReadLine();
            statusLabel.Text = "\u6b63\u5728\u8fd0\u884c";
            startButton.Enabled = false;
            stopButton.Enabled = true;
            AppendLog("Mindcraft \u5df2\u542f\u52a8\u3002");
            await Task.Delay(1200);
            OpenConsole();
        }

        private void StopMindcraft()
        {
            try
            {
                if (mindcraftProcess != null && !mindcraftProcess.HasExited)
                {
                    mindcraftProcess.Kill();
                    AppendLog("\u5df2\u53d1\u9001\u505c\u6b62\u4fe1\u53f7\u3002");
                }
            }
            catch (Exception ex)
            {
                AppendLog("\u505c\u6b62\u5931\u8d25: " + ex.Message);
            }
        }

        private void OpenConsole()
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "http://localhost:" + mindserverPort + "/",
                    UseShellExecute = true
                });
            }
            catch (Exception ex)
            {
                AppendLog("\u6253\u5f00\u63a7\u5236\u53f0\u5931\u8d25: " + ex.Message);
            }
        }

        private async Task RefreshServerStatusAsync()
        {
            try
            {
                string health = await GetTextAsync("http://localhost:" + mindserverPort + "/api/health", 800);
                if (health.Contains("\"ok\":true") && (mindcraftProcess == null || mindcraftProcess.HasExited))
                    statusLabel.Text = "\u63a7\u5236\u53f0\u5728\u7ebf";
            }
            catch
            {
                if (mindcraftProcess == null || mindcraftProcess.HasExited)
                    statusLabel.Text = "\u672a\u542f\u52a8";
            }
        }

        private void SetConnectionReady(bool ready)
        {
            connectionReady = ready;
            if (startButton != null)
                startButton.Enabled = ready && (mindcraftProcess == null || mindcraftProcess.HasExited);
        }

        private void SetBusy(bool busy)
        {
            saveButton.Enabled = !busy;
            testButton.Enabled = !busy;
            startButton.Enabled = !busy && connectionReady;
        }

        private void AppendLog(string text)
        {
            if (InvokeRequired)
            {
                BeginInvoke((Action)(() => AppendLog(text)));
                return;
            }
            logBox.AppendText("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + text + Environment.NewLine);
        }

        private async Task<string> RunNodeCaptureAsync(string arguments, int timeoutMs)
        {
            var psi = new ProcessStartInfo
            {
                FileName = nodePath,
                Arguments = arguments,
                WorkingDirectory = appDir,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };

            using (var process = new Process { StartInfo = psi })
            {
                var output = new StringBuilder();
                process.Start();
                Task<string> stdoutTask = process.StandardOutput.ReadToEndAsync();
                Task<string> stderrTask = process.StandardError.ReadToEndAsync();
                if (!process.WaitForExit(timeoutMs))
                {
                    try { process.Kill(); } catch { }
                    return "{\"ok\":false,\"error\":\"Connection test timed out.\"}";
                }
                output.Append(await stdoutTask);
                string stderr = await stderrTask;
                if (stderr.Trim().Length > 0) output.Append(stderr);
                return output.ToString();
            }
        }

        private static string Quote(string value)
        {
            return "\"" + (value ?? "").Replace("\"", "\\\"") + "\"";
        }

        private static string ExtractError(string json)
        {
            return MatchString(json, "\"error\"\\s*:\\s*\"([^\"]+)\"", json);
        }

        private static async Task<string> GetTextAsync(string url, int timeoutMs)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Timeout = timeoutMs;
            using (var res = (HttpWebResponse)await req.GetResponseAsync())
            using (var stream = res.GetResponseStream())
            using (var reader = new StreamReader(stream, Encoding.UTF8))
                return await reader.ReadToEndAsync();
        }
    }
}



