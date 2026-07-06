using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;

namespace SynaLauncherModern
{
    public class AppEntry
    {
        [STAThread]
        public static void Main()
        {
            var app = new Application();
            app.Run(new LauncherWindow());
        }
    }

    public class ConfigSnapshot
    {
        public string appDir { get; set; }
        public int mindserverPort { get; set; }
        public MinecraftConfig minecraft { get; set; }
        public ModelConfig model { get; set; }
        public VoiceConfig voice { get; set; }
        public LauncherConfig launcher { get; set; }
    }

    
    public class LauncherConfig
    {
        public string modsDir { get; set; }
        public string modJar { get; set; }
    }

    public class MinecraftConfig
    {
        public string host { get; set; }
        public object port { get; set; }
        public string minecraft_version { get; set; }
    }

    public class ModelConfig
    {
        public string api { get; set; }
        public string baseURL { get; set; }
        public string model { get; set; }
        public string apiKeyName { get; set; }
        public bool hasKey { get; set; }
        public string apiKey { get; set; }
    }

    public class VoiceConfig
    {
        public bool enabled { get; set; }
        public string voiceBaseUrl { get; set; }
        public string mindcraftUrl { get; set; }
        public string defaultAgent { get; set; }
        public string senderName { get; set; }
        public object rmsThreshold { get; set; }
        public object inputDevice { get; set; }
        public string volcAppId { get; set; }
        public string volcAccessToken { get; set; }
        public string volcVoiceId { get; set; }
        public string volcCluster { get; set; }
        public object volcSpeed { get; set; }
        public string volcAsrResourceId { get; set; }
    }

    public sealed class LauncherWindow : Window
    {
        private readonly string appDir;
        private readonly string nodePath;
        private readonly JavaScriptSerializer json = new JavaScriptSerializer();
        private Process mindcraftProcess;
        private Process voiceProcess;
        private Process asrProcess;
        private int mindserverPort = 8081;
        private bool connectionReady;
        private bool applyingConfig;
        private DateTime lastMindcraftStartAt = DateTime.MinValue;
        private string currentPage = "";
        private ConfigSnapshot currentConfig;

        private Grid contentHost;
        private TextBlock statusText;
        private TextBox logBox;
        private CheckBox autoScrollLog;
        private CheckBox showVerboseLog;

        private TextBox mcHost;
        private TextBox mcPort;
        private TextBox mcVersion;
        private TextBlock mcHint;
        private TextBox modsDirBox;
        private TextBlock modHint;

        private TextBox modelApi;
        private TextBox modelBaseUrl;
        private TextBox modelName;
        private PasswordBox modelKey;
        private TextBlock modelHint;

        private CheckBox voiceEnabled;
        private TextBox voiceBaseUrl;
        private TextBox voiceMindcraftUrl;
        private TextBox voiceAgent;
        private TextBox voiceSender;
        private TextBox voiceRms;
        private ComboBox voiceInputDevice;
        private TextBox volcAppId;
        private PasswordBox volcAccessToken;
        private TextBox volcVoiceId;
        private TextBox volcCluster;
        private TextBox volcSpeed;
        private TextBox volcAsrResourceId;
        private TextBlock voiceHint;

        private Button startButton;
        private Button stopButton;

        public LauncherWindow()
        {
            appDir = FindAppDir();
            nodePath = FindNodePath(appDir);
            BuildUi();
            LoadConfigAsync();
        }

        private string FindAppDir()
        {
            string dir = AppDomain.CurrentDomain.BaseDirectory;
            for (int i = 0; i < 8 && dir != null; i++)
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
                return File.Exists(bundled) ? bundled : "node.exe";
            }
    
            private void BuildUi()
            {
                Title = "Syna 启动器";
                Width = 1060;
                Height = 720;
                MinWidth = 920;
                MinHeight = 620;
                Background = Brush("#F7F8FA");
                FontFamily = new FontFamily("Microsoft YaHei UI");
                FontSize = 13;
    
                var shell = new Grid();
                shell.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(220) });
                shell.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                Content = shell;
    
                var side = new Border
                {
                    Background = Brushes.White,
                    BorderBrush = Brush("#E7EAF0"),
                    BorderThickness = new Thickness(0, 0, 1, 0),
                    Padding = new Thickness(18, 20, 18, 18)
                };
                Grid.SetColumn(side, 0);
                shell.Children.Add(side);
    
                var sideStack = new StackPanel();
                side.Child = sideStack;
                sideStack.Children.Add(new TextBlock
                {
                    Text = "Syna",
                    FontSize = 28,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = Brush("#14171F"),
                    Margin = new Thickness(4, 0, 0, 2)
                });
                sideStack.Children.Add(new TextBlock
                {
                    Text = "Minecraft Bot Launcher",
                    Foreground = Brush("#717784"),
                    Margin = new Thickness(5, 0, 0, 24)
                });
    
                sideStack.Children.Add(NavButton("启动", delegate { ShowLaunchPage(); }));
                sideStack.Children.Add(NavButton("模型", delegate { ShowModelPage(); }));
                sideStack.Children.Add(NavButton("语音", delegate { ShowVoicePage(); }));
                sideStack.Children.Add(NavButton("日志", delegate { ShowLogPage(); }));
    
                statusText = new TextBlock
                {
                    Text = "未启动",
                    Foreground = Brush("#717784"),
                    Margin = new Thickness(5, 36, 0, 0),
                    TextWrapping = TextWrapping.Wrap
                };
                sideStack.Children.Add(statusText);
    
                var main = new Grid { Margin = new Thickness(28, 24, 28, 24) };
                main.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
                Grid.SetColumn(main, 1);
                shell.Children.Add(main);
    
                var top = new DockPanel { Margin = new Thickness(0, 0, 0, 20) };
                Grid.SetRow(top, 0);
                main.Children.Add(top);
                top.Children.Add(new TextBlock
                {
                    Text = "控制台",
                    FontSize = 24,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = Brush("#14171F"),
                    VerticalAlignment = VerticalAlignment.Center
                });
    
                contentHost = new Grid();
                Grid.SetRow(contentHost, 1);
                main.Children.Add(contentHost);
    
                logBox = new TextBox
                {
                    IsReadOnly = true,
                    AcceptsReturn = true,
                    VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                    TextWrapping = TextWrapping.Wrap,
                    Background = Brush("#0F1117"),
                    Foreground = Brush("#E8EAED"),
                    BorderThickness = new Thickness(0),
                    Padding = new Thickness(14),
                    FontFamily = new FontFamily("Consolas")
                };
    
                ShowLaunchPage();
            }
    
            private Button NavButton(string text, RoutedEventHandler handler)
            {
                var button = new Button
                {
                    Content = text,
                    Height = 40,
                    HorizontalContentAlignment = HorizontalAlignment.Left,
                    Padding = new Thickness(14, 0, 14, 0),
                    Margin = new Thickness(0, 0, 0, 8),
                    Background = Brush("#F4F6F8"),
                    Foreground = Brush("#20242C"),
                    BorderBrush = Brush("#E6EAF0"),
                    BorderThickness = new Thickness(1)
                };
                button.Click += handler;
                return button;
            }
    
            private void ShowLaunchPage()
            {
                CaptureDraftFromControls();
                contentHost.Children.Clear();
                var grid = TwoColumnGrid();
                contentHost.Children.Add(PageScroll(grid));
    
                var card = Card("Minecraft 连接");
                Grid.SetColumn(card, 0);
                grid.Children.Add(card);
                var stack = (StackPanel)card.Child;
                mcHost = AddInput(stack, "地址", "127.0.0.1");
                mcPort = AddInput(stack, "局域网端口", "");
                mcVersion = AddInput(stack, "版本", "auto");
                mcHost.TextChanged += DirtyConnection;
                mcPort.TextChanged += DirtyConnection;
                mcVersion.TextChanged += DirtyConnection;
                mcHint = Hint("端口为空时不允许启动。打开 Minecraft 局域网后填写显示的端口。");
                stack.Children.Add(mcHint);
                stack.Children.Add(ButtonRow(new Button[] {
                    ActionButton("保存配置", SaveAllClicked, false),
                    ActionButton("测试连接", TestConnectionClicked, true)
                }));

                var modCard = Card("Syna Mod 安装");
                Grid.SetColumn(modCard, 0);
                Grid.SetRow(modCard, 1);
                grid.Children.Add(modCard);
                var modStack = (StackPanel)modCard.Child;
                modsDirBox = AddInput(modStack, "Minecraft mods 文件夹", "例如 .minecraft\\mods 或整合包的 mods");
                modHint = Hint("选择你实际启动的 Forge/整合包 mods 目录，启动器会把 synabridge mod 复制进去。换整合包时重新选一次即可。");
                modStack.Children.Add(modHint);
                modStack.Children.Add(ButtonRow(new Button[] {
                    ActionButton("选择文件夹", BrowseModsDirClicked, false),
                    ActionButton("安装/更新 Syna Mod", InstallModClicked, true)
                }));

                var actions = Card("运行");
                Grid.SetColumn(actions, 1);
                grid.Children.Add(actions);
                var run = (StackPanel)actions.Child;
                run.Children.Add(new TextBlock
                {
                    Text = "启动前会保存当前配置。模型和语音可以在左侧页面单独调整。",
                    TextWrapping = TextWrapping.Wrap,
                    Foreground = Brush("#596170"),
                    Margin = new Thickness(0, 0, 0, 18)
                });
                startButton = ActionButton("启动 Syna", StartClicked, true);
                SetConnectionReady(connectionReady);
                stopButton = ActionButton("停止", StopClicked, false);
                stopButton.IsEnabled = mindcraftProcess != null && !mindcraftProcess.HasExited;
                run.Children.Add(ButtonRow(new Button[] { startButton, stopButton, ActionButton("打开网页控制台", OpenConsoleClicked, false) }));
            ApplyConfig(currentConfig);
                currentPage = "launch";
            }
    
            private void ShowModelPage()
            {
                CaptureDraftFromControls();
                contentHost.Children.Clear();
                var card = Card("模型接入");
                contentHost.Children.Add(PageScroll(card));
                var stack = (StackPanel)card.Child;
                modelApi = AddInput(stack, "Provider", "custom / deepseek / moonshot / ollama");
                modelBaseUrl = AddInput(stack, "API 地址", "https://... 或 http://127.0.0.1:11434");
                modelName = AddInput(stack, "模型名称", "model name");
                stack.Children.Add(Label("API Key"));
                modelKey = new PasswordBox { Height = 34, Margin = new Thickness(0, 4, 0, 14), Padding = new Thickness(10, 6, 10, 6), BorderBrush = Brush("#DDE2EA") };
                stack.Children.Add(modelKey);
                modelHint = Hint("新手建议先点检测本地模型；如果使用云 API，选一个预设后只需要填模型名和 Key。高级用户再改 API 地址。");
                stack.Children.Add(modelHint);
                stack.Children.Add(ButtonRow(new Button[] {
                    ActionButton("本地 Ollama", OllamaPresetClicked, false),
                    ActionButton("LM Studio", LmStudioPresetClicked, false),
                    ActionButton("DeepSeek", DeepSeekPresetClicked, false),
                    ActionButton("OpenAI兼容", CustomPresetClicked, false)
                }));
                stack.Children.Add(ButtonRow(new Button[] {
                    ActionButton("保存模型", SaveAllClicked, true),
                    ActionButton("导入当前配置", ReloadClicked, false),
                    ActionButton("检测本地模型", DetectLocalModelClicked, false)
                }));
                ApplyConfig(currentConfig);
                currentPage = "model";
            }
    
            private void ShowVoicePage()
            {
                CaptureDraftFromControls();
                contentHost.Children.Clear();
                var grid = TwoColumnGrid();
                contentHost.Children.Add(PageScroll(grid));
    
                var tts = Card("语音合成 TTS");
                Grid.SetColumn(tts, 0);
                grid.Children.Add(tts);
                var left = (StackPanel)tts.Child;
                voiceEnabled = new CheckBox { Content = "启用语音合成", Margin = new Thickness(0, 0, 0, 14), IsChecked = true };
                left.Children.Add(voiceEnabled);
                voiceBaseUrl = AddInput(left, "本地语音服务地址", "http://127.0.0.1:8766");
                voiceMindcraftUrl = AddInput(left, "Mindcraft 地址", "http://127.0.0.1:8081");
                volcVoiceId = AddInput(left, "音色 Voice ID", "");
                volcCluster = AddInput(left, "Cluster", "volcano_icl");
                volcSpeed = AddInput(left, "语速", "1.0");
    
                var asr = Card("语音转文本 ASR");
                Grid.SetColumn(asr, 1);
                grid.Children.Add(asr);
                var right = (StackPanel)asr.Child;
                voiceAgent = AddInput(right, "目标 Bot", "syna");
                voiceSender = AddInput(right, "发送者名称", "SynaMic");
                voiceRms = AddInput(right, "麦克风阈值", "90");
                voiceInputDevice = AddCombo(right, "麦克风设备", "默认麦克风", "");
                volcAppId = AddInput(right, "火山 APP ID", "");
                right.Children.Add(Label("火山 Access Token"));
                volcAccessToken = new PasswordBox { Height = 34, Margin = new Thickness(0, 4, 0, 14), Padding = new Thickness(10, 6, 10, 6), BorderBrush = Brush("#DDE2EA") };
                right.Children.Add(volcAccessToken);
                volcAsrResourceId = AddInput(right, "ASR Resource ID", "volc.seedasr.sauc.duration");
                voiceHint = Hint("保存后会写入本机 keys.json / launcher/voice_config.json / settings.js。不会把你的 key 写进源码。");
                right.Children.Add(voiceHint);
                right.Children.Add(ButtonRow(new Button[] { ActionButton("保存语音配置", SaveAllClicked, true), ActionButton("重新导入", ReloadClicked, false) }));
                right.Children.Add(ButtonRow(new Button[] { ActionButton("启动TTS服务", StartTtsClicked, true), ActionButton("启动ASR识别", StartAsrClicked, false), ActionButton("停止语音服务", StopVoiceServicesClicked, false) }));
                right.Children.Add(ButtonRow(new Button[] { ActionButton("测试TTS", TestTtsClicked, false), ActionButton("检查ASR", TestAsrClicked, false), ActionButton("刷新麦克风", ListInputDevicesClicked, false), ActionButton("转发测试文本", TestVoiceInputClicked, false) }));
                ApplyConfig(currentConfig);
                RefreshInputDevicesAsync(false);
                currentPage = "voice";
            }
    
            private void ShowLogPage()
            {
                CaptureDraftFromControls();
                contentHost.Children.Clear();
                var grid = new Grid();
                grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
                var toolbar = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 10) };
                autoScrollLog = autoScrollLog ?? new CheckBox { Content = "自动滚动", IsChecked = true, Margin = new Thickness(0, 0, 18, 0), VerticalAlignment = VerticalAlignment.Center };
                showVerboseLog = showVerboseLog ?? new CheckBox { Content = "显示调试噪音", IsChecked = false, Margin = new Thickness(0, 0, 18, 0), VerticalAlignment = VerticalAlignment.Center };
                toolbar.Children.Add(autoScrollLog);
                toolbar.Children.Add(showVerboseLog);
                toolbar.Children.Add(ActionButton("清空日志", delegate { if (logBox != null) logBox.Clear(); }, false));
                Grid.SetRow(toolbar, 0);
                grid.Children.Add(toolbar);
                Grid.SetRow(logBox, 1);
                grid.Children.Add(logBox);
                contentHost.Children.Add(grid);
                currentPage = "log";
            }
            private Grid TwoColumnGrid()
            {
                var grid = new Grid();
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                return grid;
            }
    
            private ScrollViewer PageScroll(UIElement content)
            {
                return new ScrollViewer
                {
                    Content = content,
                    VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                    HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                    Padding = new Thickness(0, 0, 8, 6)
                };
            }
            private Border Card(string title)
            {
                var stack = new StackPanel();
                stack.Children.Add(new TextBlock
                {
                    Text = title,
                    FontSize = 18,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = Brush("#181C24"),
                    Margin = new Thickness(0, 0, 0, 18)
                });
                return new Border
                {
                    Background = Brushes.White,
                    CornerRadius = new CornerRadius(14),
                    BorderBrush = Brush("#E6EAF0"),
                    BorderThickness = new Thickness(1),
                    Padding = new Thickness(22),
                    Margin = new Thickness(0, 0, 18, 0),
                    Child = stack
                };
            }
    
            private TextBlock Label(string text)
            {
                return new TextBlock { Text = text, Foreground = Brush("#4C5564"), FontWeight = FontWeights.SemiBold };
            }
    
            private TextBlock Hint(string text)
            {
                return new TextBlock { Text = text, Foreground = Brush("#717784"), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 2, 0, 14) };
            }
    
            private TextBox AddInput(StackPanel stack, string label, string placeholder)
            {
                stack.Children.Add(Label(label));
                var box = new TextBox
                {
                    Text = placeholder,
                    Height = 34,
                    Margin = new Thickness(0, 4, 0, 14),
                    Padding = new Thickness(10, 6, 10, 6),
                    BorderBrush = Brush("#DDE2EA"),
                    Background = Brush("#FFFFFF")
                };
                stack.Children.Add(box);
                return box;
            }
            private ComboBox AddCombo(StackPanel stack, string label, string defaultText, string defaultValue)
            {
                stack.Children.Add(Label(label));
                var combo = new ComboBox
                {
                    Height = 34,
                    Margin = new Thickness(0, 4, 0, 14),
                    Padding = new Thickness(8, 4, 8, 4),
                    BorderBrush = Brush("#DDE2EA"),
                    Background = Brush("#FFFFFF")
                };
                combo.Items.Add(new ComboBoxItem { Content = defaultText, Tag = defaultValue });
                combo.SelectedIndex = 0;
                stack.Children.Add(combo);
                return combo;
            }
            private StackPanel ButtonRow(Button[] buttons)
            {
                var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 0) };
                for (int i = 0; i < buttons.Length; i++) row.Children.Add(buttons[i]);
                return row;
            }
    
            private Button ActionButton(string text, RoutedEventHandler handler, bool primary)
            {
                var button = new Button
                {
                    Content = text,
                    Height = 36,
                    MinWidth = 104,
                    Padding = new Thickness(16, 0, 16, 0),
                    Margin = new Thickness(0, 0, 10, 0),
                    Background = primary ? Brush("#1668E3") : Brush("#F4F6F8"),
                    Foreground = primary ? Brushes.White : Brush("#20242C"),
                    BorderBrush = primary ? Brush("#1668E3") : Brush("#DDE2EA"),
                    BorderThickness = new Thickness(1)
                };
                button.Click += handler;
                return button;
            }
    
            private SolidColorBrush Brush(string hex)
            {
                return new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
            }
    
            private async void LoadConfigAsync()
            {
                await LoadConfigAsyncTask();
            }

            private async Task LoadConfigAsyncTask()
            {
                try
                {
                    string output = await RunNodeAsync("launcher/config_bridge.mjs read", null, 8000);
                    var cfg = json.Deserialize<ConfigSnapshot>(output);
                    ApplyConfig(cfg);
                    AppendLog("已导入本机配置。");
                }
                catch (Exception ex)
                {
                    AppendLog("导入配置失败：配置文件内容无法解析，请检查 keys.json / profiles/syna.json。错误摘要: " + FriendlyError(ex.Message));
                }
            }
    
            private void ApplyConfig(ConfigSnapshot cfg)
            {
                if (cfg == null) return;
                currentConfig = cfg;
                mindserverPort = cfg.mindserverPort > 0 ? cfg.mindserverPort : 8081;
                applyingConfig = true;
                try
                {
                if (cfg.minecraft != null)
                {
                    SetText(mcHost, cfg.minecraft.host, "127.0.0.1");
                    SetText(mcPort, ObjectToString(cfg.minecraft.port), "");
                    SetText(mcVersion, cfg.minecraft.minecraft_version, "auto");
                }
                if (cfg.model != null)
                {
                    SetText(modelApi, cfg.model.api, "custom");
                    SetText(modelBaseUrl, cfg.model.baseURL, "");
                    SetText(modelName, cfg.model.model, "");
                    if (modelKey != null) modelKey.Password = cfg.model.apiKey ?? "";
                    if (modelHint != null) modelHint.Text = "当前 Key: " + (cfg.model.hasKey ? "已配置" : "未配置") + "，变量名: " + (cfg.model.apiKeyName ?? "自动");
                }
                if (cfg.launcher != null)
                {
                    SetText(modsDirBox, cfg.launcher.modsDir, "");
                    if (modHint != null)
                    {
                        modHint.Text = String.IsNullOrWhiteSpace(cfg.launcher.modJar)
                            ? "未找到随项目打包的 synabridge jar，请先构建 syna_mod。"
                            : "已找到 Syna Mod: " + Path.GetFileName(cfg.launcher.modJar);
                    }
                }
                if (cfg.voice != null)
                {
                    if (voiceEnabled != null) voiceEnabled.IsChecked = cfg.voice.enabled;
                    SetText(voiceBaseUrl, cfg.voice.voiceBaseUrl, "http://127.0.0.1:8766");
                    SetText(voiceMindcraftUrl, cfg.voice.mindcraftUrl, "http://127.0.0.1:" + mindserverPort);
                    SetText(voiceAgent, cfg.voice.defaultAgent, "syna");
                    SetText(voiceSender, cfg.voice.senderName, "SynaMic");
                    SetText(voiceRms, ObjectToString(cfg.voice.rmsThreshold), "90");
                    SetComboValue(voiceInputDevice, ObjectToString(cfg.voice.inputDevice), "");
                    SetText(volcAppId, cfg.voice.volcAppId, "");
                    if (volcAccessToken != null) volcAccessToken.Password = cfg.voice.volcAccessToken ?? "";
                    SetText(volcVoiceId, cfg.voice.volcVoiceId, "");
                    SetText(volcCluster, cfg.voice.volcCluster, "volcano_icl");
                    SetText(volcSpeed, ObjectToString(cfg.voice.volcSpeed), "1.0");
                    SetText(volcAsrResourceId, cfg.voice.volcAsrResourceId, "volc.seedasr.sauc.duration");
                }
            }
            finally
            {
                applyingConfig = false;
            }
        }

        private void CaptureDraftFromControls()
        {
            if (!Dispatcher.CheckAccess())
            {
                Dispatcher.Invoke((Action)CaptureDraftFromControls);
                return;
            }
            if (applyingConfig || String.IsNullOrEmpty(currentPage)) return;
            if (currentConfig == null) currentConfig = new ConfigSnapshot();

            if (currentPage == "launch")
            {
                if (currentConfig.minecraft == null) currentConfig.minecraft = new MinecraftConfig();
                if (mcHost != null) currentConfig.minecraft.host = GetText(mcHost, currentConfig.minecraft.host ?? "127.0.0.1");
                if (mcPort != null) currentConfig.minecraft.port = mcPort.Text == null ? "" : mcPort.Text.Trim();
                if (mcVersion != null) currentConfig.minecraft.minecraft_version = GetText(mcVersion, currentConfig.minecraft.minecraft_version ?? "auto");
                if (currentConfig.launcher == null) currentConfig.launcher = new LauncherConfig();
                if (modsDirBox != null) currentConfig.launcher.modsDir = GetText(modsDirBox, currentConfig.launcher.modsDir ?? "");
                return;
            }

            if (currentPage == "model")
            {
                if (currentConfig.model == null) currentConfig.model = new ModelConfig();
                if (modelApi != null) currentConfig.model.api = GetText(modelApi, currentConfig.model.api ?? "custom");
                if (modelBaseUrl != null) currentConfig.model.baseURL = GetText(modelBaseUrl, currentConfig.model.baseURL ?? "");
                if (modelName != null) currentConfig.model.model = GetText(modelName, currentConfig.model.model ?? "");
                if (modelKey != null) currentConfig.model.apiKey = modelKey.Password ?? "";
                return;
            }

            if (currentPage == "voice")
            {
                if (currentConfig.voice == null) currentConfig.voice = new VoiceConfig();
                if (voiceEnabled != null) currentConfig.voice.enabled = voiceEnabled.IsChecked == true;
                if (voiceBaseUrl != null) currentConfig.voice.voiceBaseUrl = GetText(voiceBaseUrl, currentConfig.voice.voiceBaseUrl ?? "http://127.0.0.1:8766");
                if (voiceMindcraftUrl != null) currentConfig.voice.mindcraftUrl = GetText(voiceMindcraftUrl, currentConfig.voice.mindcraftUrl ?? "http://127.0.0.1:" + mindserverPort);
                if (voiceAgent != null) currentConfig.voice.defaultAgent = GetText(voiceAgent, currentConfig.voice.defaultAgent ?? "syna");
                if (voiceSender != null) currentConfig.voice.senderName = GetText(voiceSender, currentConfig.voice.senderName ?? "SynaMic");
                if (voiceRms != null) currentConfig.voice.rmsThreshold = GetText(voiceRms, ObjectToString(currentConfig.voice.rmsThreshold));
                if (voiceInputDevice != null) currentConfig.voice.inputDevice = GetComboValue(voiceInputDevice, ObjectToString(currentConfig.voice.inputDevice));
                if (volcAppId != null) currentConfig.voice.volcAppId = GetText(volcAppId, currentConfig.voice.volcAppId ?? "");
                if (volcAccessToken != null) currentConfig.voice.volcAccessToken = volcAccessToken.Password ?? "";
                if (volcVoiceId != null) currentConfig.voice.volcVoiceId = GetText(volcVoiceId, currentConfig.voice.volcVoiceId ?? "");
                if (volcCluster != null) currentConfig.voice.volcCluster = GetText(volcCluster, currentConfig.voice.volcCluster ?? "volcano_icl");
                if (volcSpeed != null) currentConfig.voice.volcSpeed = GetText(volcSpeed, ObjectToString(currentConfig.voice.volcSpeed));
                if (volcAsrResourceId != null) currentConfig.voice.volcAsrResourceId = GetText(volcAsrResourceId, currentConfig.voice.volcAsrResourceId ?? "volc.seedasr.sauc.duration");
            }
        }
        private string ObjectToString(object value)
        {
            return value == null ? "" : Convert.ToString(value, System.Globalization.CultureInfo.InvariantCulture);
        }

        private void SetText(TextBox box, string value, string fallback)
        {
            if (box != null) box.Text = String.IsNullOrEmpty(value) ? fallback : value;
        }

        private void SetComboValue(ComboBox combo, string value, string fallback)
        {
            if (combo == null) return;
            string target = String.IsNullOrWhiteSpace(value) ? fallback : value.Trim();
            if (String.IsNullOrWhiteSpace(target))
            {
                combo.SelectedIndex = combo.Items.Count > 0 ? 0 : -1;
                return;
            }
            for (int i = 0; i < combo.Items.Count; i++)
            {
                ComboBoxItem item = combo.Items[i] as ComboBoxItem;
                if (item == null) continue;
                string tag = item.Tag == null ? "" : Convert.ToString(item.Tag, System.Globalization.CultureInfo.InvariantCulture);
                if (String.Equals(tag, target, StringComparison.OrdinalIgnoreCase))
                {
                    combo.SelectedIndex = i;
                    return;
                }
            }
            combo.Items.Add(new ComboBoxItem { Content = "设备 " + target + "（已保存）", Tag = target });
            combo.SelectedIndex = combo.Items.Count - 1;
        }
        private void DirtyConnection(object sender, TextChangedEventArgs e)
        {
            if (applyingConfig) return;
            SetConnectionReady(false);
        }

        private async void TestTtsClicked(object sender, RoutedEventArgs e)
        {
            try
            {
                CaptureDraftFromControls();
                if (!await StartTtsServiceAsync(false)) return;
                string url = GetText(voiceBaseUrl, "http://127.0.0.1:8766").TrimEnd('/') + "/say";
                string body = "{\"text\":\"Syna 语音合成测试，如果你听到这句话，TTS 正常。\",\"interrupt\":true}";
                string result = await PostJsonAsync(url, body, 8000);
                voiceHint.Text = "TTS 测试已发送，请听是否有声音。";
                AppendLog("[VoiceTest] TTS /say 返回: " + Compact(result));
            }
            catch (Exception ex)
            {
                voiceHint.Text = "TTS 测试失败：服务启动失败或火山配置/音频播放有问题，请看日志。";
                AppendLog("[VoiceTest] TTS 失败: " + FriendlyError(ex.Message));
            }
        }

        private async void TestAsrClicked(object sender, RoutedEventArgs e)
        {
            try
            {
                if (!await StartAsrServiceAsync(false)) return;
                string result = await GetTextUrlAsync("http://127.0.0.1:8089/status", 3000);
                voiceHint.Text = "ASR 控制端点在线。";
                AppendLog("[VoiceTest] ASR /status 返回: " + Compact(result));
            }
            catch (Exception ex)
            {
                voiceHint.Text = "ASR 未在线：服务启动失败，可能是麦克风占用或权限问题，请看日志。";
                AppendLog("[VoiceTest] ASR 检查失败: " + FriendlyError(ex.Message));
            }
        }

        private async void TestVoiceInputClicked(object sender, RoutedEventArgs e)
        {
            try
            {
                CaptureDraftFromControls();
                string url = GetText(voiceBaseUrl, "http://127.0.0.1:8766").TrimEnd('/') + "/voice-input";
                string agent = GetText(voiceAgent, "syna");
                string senderName = GetText(voiceSender, "SynaMic");
                string body = "{\"text\":\"这是启动器语音输入测试，请简单回应一句。\",\"agent_name\":\"" + JsonEscape(agent) + "\",\"from\":\"" + JsonEscape(senderName) + "\",\"interrupt\":true}";
                string result = await PostJsonAsync(url, body, 8000);
                voiceHint.Text = "语音输入测试已转发，请看 bot 是否回应。";
                AppendLog("[VoiceTest] /voice-input 返回: " + Compact(result));
            }
            catch (Exception ex)
            {
                voiceHint.Text = "语音输入测试失败：请确认 TTS 服务、Mindcraft 和 bot 都已启动。";
                AppendLog("[VoiceTest] 语音输入失败: " + FriendlyError(ex.Message));
            }
        }

        private async void StartTtsClicked(object sender, RoutedEventArgs e)
        {
            await StartTtsServiceAsync(true);
        }

        private async void StartAsrClicked(object sender, RoutedEventArgs e)
        {
            await StartAsrServiceAsync(true);
        }

        private void StopVoiceServicesClicked(object sender, RoutedEventArgs e)
        {
            StopManagedProcess(ref voiceProcess, "TTS 语音服务");
            StopManagedProcess(ref asrProcess, "ASR 麦克风识别");
            if (voiceHint != null) voiceHint.Text = "已尝试停止由启动器启动的语音服务。";
        }

        private async Task<bool> StartTtsServiceAsync(bool showMessage)
        {
            CaptureDraftFromControls();
            string baseUrl = GetText(voiceBaseUrl, "http://127.0.0.1:8766").TrimEnd('/');
            if (await CheckTtsReadyAsync(baseUrl, "TTS 已在线")) return true;

            if (voiceProcess != null && !voiceProcess.HasExited)
            {
                AppendLog("[Voice] TTS 服务正在启动中。");
                return true;
            }

            string port = GetPortFromUrl(baseUrl, "8766");
            string args = "services/syna_voice_server.py --host 0.0.0.0 --port " + port
                + " --mindcraft-url " + Quote(GetText(voiceMindcraftUrl, "http://127.0.0.1:" + mindserverPort))
                + " --default-agent " + Quote(GetText(voiceAgent, "syna"))
                + " --volc-app-id " + Quote(GetText(volcAppId, ""))
                + " --volc-access-token " + Quote(volcAccessToken == null ? "" : volcAccessToken.Password)
                + " --volc-voice-id " + Quote(GetText(volcVoiceId, ""))
                + " --volc-cluster " + Quote(GetText(volcCluster, "volcano_icl"))
                + " --speed " + GetText(volcSpeed, "1");
            voiceProcess = StartPythonProcess("TTS", args);
            if (voiceHint != null) voiceHint.Text = "正在启动 TTS 服务...";
            await Task.Delay(1800);
            if (await CheckTtsReadyAsync(baseUrl, "TTS 启动成功"))
            {
                if (voiceHint != null) voiceHint.Text = "TTS 服务已启动，可以点击测试TTS。";
                return true;
            }
            if (voiceHint != null) voiceHint.Text = "TTS 服务启动失败，可能是旧服务占用 8766 或端口无法访问。";
            return false;
        }

        private async Task<bool> CheckTtsReadyAsync(string baseUrl, string label)
        {
            try
            {
                string ready = await GetTextUrlAsync(baseUrl + "/ready", 1200);
                AppendLog("[Voice] " + label + ": " + Compact(ready));
                if (voiceHint != null) voiceHint.Text = "TTS service is online.";
                return true;
            }
            catch { }

            try
            {
                string health = await GetTextUrlAsync(baseUrl + "/health", 800);
                AppendLog("[Voice] Old or unhealthy TTS service is occupying this port: " + Compact(health));
                if (voiceHint != null) voiceHint.Text = "TTS port is occupied by an old/unhealthy service. Stop old voice service first.";
            }
            catch { }
            return false;
        }
        private string AsrInputDeviceArg()
        {
            string value = GetComboValue(voiceInputDevice, "");
            int device;
            if (Int32.TryParse(value, out device) && device >= 0)
                return " --input-device " + device.ToString(System.Globalization.CultureInfo.InvariantCulture);
            return "";
        }
        private async void ListInputDevicesClicked(object sender, RoutedEventArgs e)
        {
            await RefreshInputDevicesTaskAsync(true);
        }

        private async void RefreshInputDevicesAsync(bool showMessage)
        {
            await RefreshInputDevicesTaskAsync(showMessage);
        }

        private async Task RefreshInputDevicesTaskAsync(bool showMessage)
        {
            if (voiceInputDevice == null) return;
            string selected = GetComboValue(voiceInputDevice, "");
            try
            {
                if (showMessage && voiceHint != null) voiceHint.Text = "正在刷新麦克风设备...";
                string output = await RunPythonOnceAsync("services/syna_asr_server.py --list-devices", 8000);
                AppendLog("[Voice] 麦克风设备列表:" + Environment.NewLine + output);
                List<ComboBoxItem> items = BuildInputDeviceItems(output);
                voiceInputDevice.Items.Clear();
                foreach (ComboBoxItem item in items) voiceInputDevice.Items.Add(item);
                SetComboValue(voiceInputDevice, selected, "");
                if (showMessage && voiceHint != null) voiceHint.Text = "麦克风设备已刷新，可以直接在下拉框选择。";
            }
            catch (Exception ex)
            {
                string friendly = FriendlyError(ex.Message);
                AppendLog("[Voice] 刷新麦克风失败: " + friendly);
                if (voiceInputDevice.Items.Count == 0) voiceInputDevice.Items.Add(new ComboBoxItem { Content = "默认麦克风", Tag = "" });
                if (voiceInputDevice.SelectedIndex < 0) voiceInputDevice.SelectedIndex = 0;
                if (showMessage && voiceHint != null) voiceHint.Text = "刷新麦克风失败，请确认 pyaudio 可用。";
            }
        }

        private List<ComboBoxItem> BuildInputDeviceItems(string output)
        {
            var items = new List<ComboBoxItem>();
            items.Add(new ComboBoxItem { Content = "默认麦克风", Tag = "" });
            if (String.IsNullOrWhiteSpace(output)) return items;
            string[] lines = Regex.Split(output, "\r?\n");
            foreach (string line in lines)
            {
                Match match = Regex.Match(line, @"^\s*(\*)?\s*(\d+)\s*:\s*(.+)$");
                if (!match.Success) continue;
                string index = match.Groups[2].Value;
                string name = match.Groups[3].Value.Trim();
                string suffix = match.Groups[1].Success ? "（系统默认）" : "";
                items.Add(new ComboBoxItem { Content = index + ": " + name + suffix, Tag = index });
            }
            return items;
        }

        private async Task<bool> StartAsrServiceAsync(bool showMessage)
        {
            CaptureDraftFromControls();
            try
            {
                string status = await GetTextUrlAsync("http://127.0.0.1:8089/status", 1200);
                if (status.Contains("\"micStatus\"") && Regex.IsMatch(status, @"""allInputDevices""\s*:\s*true"))
                {
                    AppendLog("[Voice] ASR online: " + Compact(status));
                    if (voiceHint != null) voiceHint.Text = "ASR speech recognition service is online.";
                    return true;
                }
                AppendLog("[Voice] Old ASR instance detected; starting a fresh all-device ASR.");
                await RunPowerShellOnceAsync("stop_syna_asr.ps1", 4000);
            }
            catch { }

            if (asrProcess != null && !asrProcess.HasExited)
            {
                AppendLog("[Voice] ASR 服务正在启动中。");
                return true;
            }

            string inputDeviceArg = AsrInputDeviceArg();
            string args = "services/syna_asr_server.py"
                + " --mindcraft-url " + Quote(GetText(voiceMindcraftUrl, "http://127.0.0.1:" + mindserverPort))
                + " --default-agent " + Quote(GetText(voiceAgent, "syna"))
                + " --sender-name " + Quote(GetText(voiceSender, "SynaMic"))
                + " --voice-base-url " + Quote(GetText(voiceBaseUrl, "http://127.0.0.1:8766"))
                + " --rms-threshold " + GetText(voiceRms, "90")
                + " --max-recording-seconds 8"
                + inputDeviceArg
                + (String.IsNullOrWhiteSpace(inputDeviceArg) ? " --all-input-devices" : "")
                + " --always-listen"
                + " --volc-app-id " + Quote(GetText(volcAppId, ""))
                + " --volc-access-token " + Quote(volcAccessToken == null ? "" : volcAccessToken.Password)
                + " --volc-asr-resource-id " + Quote(GetText(volcAsrResourceId, "volc.seedasr.sauc.duration"));
            asrProcess = StartPythonProcess("ASR", args);
            if (voiceHint != null) voiceHint.Text = "正在启动 ASR 麦克风识别...";
            await Task.Delay(2500);
            try
            {
                string status = await GetTextUrlAsync("http://127.0.0.1:8089/status", 2500);
                AppendLog("[Voice] ASR 启动成功: " + Compact(status));
                if (voiceHint != null) voiceHint.Text = "ASR 已启动。对麦克风说话，识别后会转发给 bot。";
                return true;
            }
            catch (Exception ex)
            {
                if (voiceHint != null) voiceHint.Text = "ASR 启动后仍无法连接，请查看日志。";
                AppendLog("[Voice] ASR 启动检查失败: " + FriendlyError(ex.Message));
                return false;
            }
        }

        private Process StartPythonProcess(string tag, string arguments)
        {
            var psi = new ProcessStartInfo
            {
                FileName = "python",
                Arguments = arguments,
                WorkingDirectory = appDir,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            psi.EnvironmentVariables["PYTHONUTF8"] = "1";
            psi.EnvironmentVariables["PYTHONIOENCODING"] = "utf-8";
            var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
            process.OutputDataReceived += delegate(object s, DataReceivedEventArgs ev) { if (ev.Data != null) AppendLog("[" + tag + "] " + ev.Data); };
            process.ErrorDataReceived += delegate(object s, DataReceivedEventArgs ev) { if (ev.Data != null) AppendLog("[" + tag + "] " + ev.Data); };
            process.Exited += delegate { AppendLog("[" + tag + "] 服务已退出。"); };
            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            AppendLog("[Voice] 已启动 " + tag + " 服务进程。");
            return process;
        }

        private void StopManagedProcess(ref Process process, string name)
        {
            try
            {
                if (process != null && !process.HasExited)
                {
                    process.Kill();
                    AppendLog("[Voice] 已停止 " + name + "。");
                }
                else
                {
                    AppendLog("[Voice] " + name + " 没有由启动器启动的运行进程。");
                }
            }
            catch (Exception ex)
            {
                AppendLog("[Voice] 停止 " + name + " 失败: " + FriendlyError(ex.Message));
            }
        }

        private string GetPortFromUrl(string url, string fallback)
        {
            try
            {
                var uri = new Uri(url);
                return uri.Port > 0 ? uri.Port.ToString() : fallback;
            }
            catch
            {
                return fallback;
            }
        }

        private void ApplyModelPreset(string api, string baseUrl, string model, string key)
        {
            if (modelApi != null) modelApi.Text = api;
            if (modelBaseUrl != null) modelBaseUrl.Text = baseUrl;
            if (modelName != null && String.IsNullOrWhiteSpace(modelName.Text)) modelName.Text = model;
            if (modelKey != null && !String.IsNullOrWhiteSpace(key)) modelKey.Password = key;
            if (modelHint != null) modelHint.Text = "已填入预设。确认模型名和 Key 后点击保存模型。";
        }

        private void OllamaPresetClicked(object sender, RoutedEventArgs e)
        {
            ApplyModelPreset("ollama", "http://127.0.0.1:11434", "llama3.1", "");
        }

        private void LmStudioPresetClicked(object sender, RoutedEventArgs e)
        {
            ApplyModelPreset("custom", "http://127.0.0.1:1234/v1", "", "local");
        }

        private void DeepSeekPresetClicked(object sender, RoutedEventArgs e)
        {
            ApplyModelPreset("deepseek", "https://api.deepseek.com", "deepseek-chat", "");
        }

        private void CustomPresetClicked(object sender, RoutedEventArgs e)
        {
            ApplyModelPreset("custom", "", "", "");
        }

        private void BrowseModsDirClicked(object sender, RoutedEventArgs e)
        {
            using (var dialog = new System.Windows.Forms.FolderBrowserDialog())
            {
                dialog.Description = "选择 Minecraft 或整合包的 mods 文件夹";
                dialog.ShowNewFolderButton = true;
                if (modsDirBox != null && Directory.Exists(modsDirBox.Text)) dialog.SelectedPath = modsDirBox.Text;
                if (dialog.ShowDialog() == System.Windows.Forms.DialogResult.OK)
                {
                    modsDirBox.Text = dialog.SelectedPath;
                    if (modHint != null) modHint.Text = "已选择 mods 文件夹，点击安装/更新 Syna Mod。";
                    CaptureDraftFromControls();
                }
            }
        }

        private async void InstallModClicked(object sender, RoutedEventArgs e)
        {
            try
            {
                CaptureDraftFromControls();
                string modsDir = GetText(modsDirBox, "");
                if (String.IsNullOrWhiteSpace(modsDir))
                {
                    if (modHint != null) modHint.Text = "请先选择 Minecraft 的 mods 文件夹。";
                    return;
                }
                string payload = "{\"modsDir\":" + JsonString(modsDir) + "}";
                string output = await RunNodeAsync("launcher/config_bridge.mjs install-mod", payload, 8000);
                var result = json.Deserialize<Dictionary<string, object>>(output);
                string target = result != null && result.ContainsKey("target") ? Convert.ToString(result["target"]) : modsDir;
                if (modHint != null) modHint.Text = "Syna Mod 已安装到: " + target;
                AppendLog("Syna Mod 已安装到: " + target);
                await LoadConfigAsyncTask();
            }
            catch (Exception ex)
            {
                string friendly = FriendlyError(ex.Message);
                if (modHint != null) modHint.Text = friendly;
                AppendLog("安装 Syna Mod 失败: " + friendly);
            }
        }
        private async void SaveAllClicked(object sender, RoutedEventArgs e)
        {
            await SaveAllAsync(true);
        }

        private async void ReloadClicked(object sender, RoutedEventArgs e)
        {
            LoadConfigAsync();
            await Task.Delay(1);
        }

        private async void DetectLocalModelClicked(object sender, RoutedEventArgs e)
        {
            try
            {
                modelHint.Text = "正在检测本地模型服务...";
                string output = await RunNodeAsync("launcher/config_bridge.mjs detect-local-model", null, 5000);
                var result = json.Deserialize<Dictionary<string, object>>(output);
                if (result.ContainsKey("found") && Convert.ToBoolean(result["found"]))
                {
                    modelApi.Text = Convert.ToString(result["api"]);
                    modelBaseUrl.Text = Convert.ToString(result["baseURL"]);
                    modelName.Text = Convert.ToString(result["model"]);
                    if (result.ContainsKey("apiKey")) modelKey.Password = Convert.ToString(result["apiKey"]);
                    modelHint.Text = "已填入本地模型，确认无误后点保存模型。";
                    AppendLog("检测到本地模型: " + modelBaseUrl.Text + " / " + modelName.Text);
                }
                else
                {
                    modelHint.Text = result.ContainsKey("message") ? Convert.ToString(result["message"]) : "未检测到本地模型服务。";
                }
            }
            catch (Exception ex)
            {
                modelHint.Text = "检测失败: " + ex.Message;
                AppendLog(modelHint.Text);
            }
        }

        private async Task SaveAllAsync(bool showMessage)
        {
            try
            {
                CaptureDraftFromControls();
                string payload = BuildSavePayload();
                await RunNodeAsync("launcher/config_bridge.mjs save", payload, 8000);
                AppendLog("配置已保存。");
                if (showMessage) MessageBox.Show(this, "配置已保存，下次打开会自动读取。", "保存成功", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            catch (Exception ex)
            {
                string friendly = FriendlyError(ex.Message);
                AppendLog("保存失败: " + friendly);
                if (showMessage) MessageBox.Show(this, friendly, "保存失败", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private string FriendlyError(string message)
        {
            string text = message ?? "";
            if (text.Contains("baseURL is required"))
                return "模型 API 地址为空。请打开“模型”页填写 API 地址和模型名，或者点击“导入当前配置”后再保存。";
            if (text.Contains("model is required"))
                return "模型名称为空。请打开“模型”页填写模型名称，或者点击“导入当前配置”后再保存。";
            if (text.Contains("Minecraft port"))
                return "Minecraft 局域网端口需要填写 1-65535 的数字。";
            if (text.Contains("\"error\""))
                return ExtractJsonError(text);
            return "保存失败：" + text;
        }
        private string BuildSavePayload()
        {
            string savedHost = currentConfig != null && currentConfig.minecraft != null ? currentConfig.minecraft.host : "127.0.0.1";
            string savedPort = currentConfig != null && currentConfig.minecraft != null ? ObjectToString(currentConfig.minecraft.port) : "";
            string savedVersion = currentConfig != null && currentConfig.minecraft != null ? currentConfig.minecraft.minecraft_version : "auto";

            string savedApi = currentConfig != null && currentConfig.model != null ? currentConfig.model.api : "custom";
            string savedBase = currentConfig != null && currentConfig.model != null ? currentConfig.model.baseURL : "";
            string savedModel = currentConfig != null && currentConfig.model != null ? currentConfig.model.model : "";
            string savedKey = currentConfig != null && currentConfig.model != null ? currentConfig.model.apiKey : "";

            VoiceConfig v = currentConfig != null ? currentConfig.voice : null;
            bool savedVoiceEnabled = v == null || v.enabled;
            string savedVoiceBase = v != null ? v.voiceBaseUrl : "http://127.0.0.1:8766";
            string savedMindcraftUrl = v != null ? v.mindcraftUrl : "http://127.0.0.1:" + mindserverPort;
            string savedAgent = v != null ? v.defaultAgent : "syna";
            string savedSender = v != null ? v.senderName : "SynaMic";
            string savedRms = v != null ? ObjectToString(v.rmsThreshold) : "90";
            string savedInputDevice = v != null ? ObjectToString(v.inputDevice) : "";
            string savedAppId = v != null ? v.volcAppId : "";
            string savedToken = v != null ? v.volcAccessToken : "";
            string savedVoiceId = v != null ? v.volcVoiceId : "";
            string savedCluster = v != null ? v.volcCluster : "volcano_icl";
            string savedSpeed = v != null ? ObjectToString(v.volcSpeed) : "1.0";
            string savedResource = v != null ? v.volcAsrResourceId : "volc.seedasr.sauc.duration";

            var sb = new StringBuilder();
            sb.Append("{");
            sb.Append("\"minecraft\":{");
            sb.Append("\"host\":").Append(JsonString(GetText(mcHost, savedHost))).Append(',');
            sb.Append("\"port\":").Append(JsonNumberOrNull(GetText(mcPort, savedPort))).Append(',');
            sb.Append("\"minecraft_version\":").Append(JsonString(GetText(mcVersion, savedVersion)));
            sb.Append("},");
            string savedModsDir = currentConfig != null && currentConfig.launcher != null ? currentConfig.launcher.modsDir : "";
            sb.Append("\"launcher\":{");
            sb.Append("\"modsDir\":").Append(JsonString(GetText(modsDirBox, savedModsDir)));
            sb.Append("},");
            sb.Append("\"model\":{");
            sb.Append("\"api\":").Append(JsonString(GetText(modelApi, savedApi))).Append(',');
            sb.Append("\"baseURL\":").Append(JsonString(GetText(modelBaseUrl, savedBase))).Append(',');
            sb.Append("\"model\":").Append(JsonString(GetText(modelName, savedModel))).Append(',');
            sb.Append("\"apiKey\":").Append(JsonString(modelKey == null ? savedKey : modelKey.Password)).Append(',');
            sb.Append("\"params\":{\"temperature\":0.6}");
            sb.Append("},");
            sb.Append("\"voice\":{");
            sb.Append("\"enabled\":").Append(voiceEnabled == null ? (savedVoiceEnabled ? "true" : "false") : (voiceEnabled.IsChecked == true ? "true" : "false")).Append(',');
            sb.Append("\"voiceBaseUrl\":").Append(JsonString(GetText(voiceBaseUrl, savedVoiceBase))).Append(',');
            sb.Append("\"mindcraftUrl\":").Append(JsonString(GetText(voiceMindcraftUrl, savedMindcraftUrl))).Append(',');
            sb.Append("\"defaultAgent\":").Append(JsonString(GetText(voiceAgent, savedAgent))).Append(',');
            sb.Append("\"senderName\":").Append(JsonString(GetText(voiceSender, savedSender))).Append(',');
            sb.Append("\"rmsThreshold\":").Append(JsonNumberOrDefault(GetText(voiceRms, savedRms), "90")).Append(',');
            sb.Append("\"inputDevice\":").Append(JsonNumberOrNull(GetComboValue(voiceInputDevice, savedInputDevice))).Append(',');
            sb.Append("\"volcAppId\":").Append(JsonString(GetText(volcAppId, savedAppId))).Append(',');
            sb.Append("\"volcAccessToken\":").Append(JsonString(volcAccessToken == null ? savedToken : volcAccessToken.Password)).Append(',');
            sb.Append("\"volcVoiceId\":").Append(JsonString(GetText(volcVoiceId, savedVoiceId))).Append(',');
            sb.Append("\"volcCluster\":").Append(JsonString(GetText(volcCluster, savedCluster))).Append(',');
            sb.Append("\"volcSpeed\":").Append(JsonNumberOrDefault(GetText(volcSpeed, savedSpeed), "1.0")).Append(',');
            sb.Append("\"volcAsrResourceId\":").Append(JsonString(GetText(volcAsrResourceId, savedResource)));
            sb.Append("}");
            sb.Append("}");
            return sb.ToString();
        }

        private string GetText(TextBox box, string fallback)
        {
            if (!Dispatcher.CheckAccess()) return Dispatcher.Invoke(() => GetText(box, fallback));
            if (box == null || String.IsNullOrWhiteSpace(box.Text)) return fallback;
            return box.Text.Trim();
        }

        private string GetComboValue(ComboBox combo, string fallback)
        {
            if (!Dispatcher.CheckAccess()) return Dispatcher.Invoke(() => GetComboValue(combo, fallback));
            if (combo == null) return fallback;
            ComboBoxItem item = combo.SelectedItem as ComboBoxItem;
            if (item != null)
            {
                string tag = item.Tag == null ? "" : Convert.ToString(item.Tag, System.Globalization.CultureInfo.InvariantCulture);
                return String.IsNullOrWhiteSpace(tag) ? fallback : tag.Trim();
            }
            string text = combo.Text;
            if (String.IsNullOrWhiteSpace(text)) return fallback;
            Match match = Regex.Match(text, @"^\s*(\d+)\s*[:：]");
            return match.Success ? match.Groups[1].Value : text.Trim();
        }
        private string JsonString(string value)
        {
            return "\"" + (value ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "").Replace("\n", "\\n") + "\"";
        }

        private string JsonNumberOrNull(string value)
        {
            int port;
            if (Int32.TryParse(value, out port) && port > 0 && port < 65536) return Convert.ToString(port);
            return "null";
        }

        private string JsonNumberOrDefault(string value, string fallback)
        {
            double number;
            if (Double.TryParse(value, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out number))
                return number.ToString(System.Globalization.CultureInfo.InvariantCulture);
            return fallback;
        }

        private async void TestConnectionClicked(object sender, RoutedEventArgs e)
        {
            await SaveAllAsync(false);
            int port;
            if (!Int32.TryParse(GetText(mcPort, ""), out port) || port < 1 || port > 65535)
            {
                mcHint.Text = "请先填写 1-65535 的局域网端口。";
                SetConnectionReady(false);
                return;
            }
            try
            {
                mcHint.Text = "正在测试连接...";
                string args = "launcher/test_minecraft_connection.mjs " + Quote(GetText(mcHost, "127.0.0.1")) + " " + port + " " + Quote(GetText(mcVersion, "auto"));
                string output = await RunNodeAsync(args, null, 6000);
                if (output.Contains("\"ok\":true"))
                {
                    mcHint.Text = "连接成功，可以启动。";
                    SetConnectionReady(true);
                    AppendLog("Minecraft 连接测试成功。");
                    await SaveAllAsync(false);
                }
                else
                {
                    mcHint.Text = ExtractJsonError(output);
                    SetConnectionReady(false);
                    AppendLog("连接测试失败: " + mcHint.Text);
                }
            }
            catch (Exception ex)
            {
                mcHint.Text = "连接测试失败: " + ex.Message;
                SetConnectionReady(false);
                AppendLog(mcHint.Text);
            }
        }

        private async void StartClicked(object sender, RoutedEventArgs e)
        {
            if (!connectionReady)
            {
                mcHint.Text = "请先测试 Minecraft 连接。";
                return;
            }
            if (mindcraftProcess != null && !mindcraftProcess.HasExited)
            {
                AppendLog("Mindcraft 已经在运行。");
                return;
            }
            await SaveAllAsync(false);
            if (currentConfig != null && currentConfig.voice != null && currentConfig.voice.enabled)
            {
                AppendLog("正在启动语音服务...");
                await StartTtsServiceAsync(false);
                await StartAsrServiceAsync(false);
            }
            var psi = new ProcessStartInfo
            {
                FileName = nodePath,
                Arguments = "main.js",
                WorkingDirectory = appDir,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            mindcraftProcess = new Process { StartInfo = psi, EnableRaisingEvents = true };
            mindcraftProcess.OutputDataReceived += delegate(object s, DataReceivedEventArgs ev) { if (ev.Data != null) AppendLog(ev.Data); };
            mindcraftProcess.ErrorDataReceived += delegate(object s, DataReceivedEventArgs ev) { if (ev.Data != null) AppendLog(ev.Data); };
            mindcraftProcess.Exited += delegate { Dispatcher.BeginInvoke((Action)delegate { statusText.Text = "已停止"; SetConnectionReady(true); if (stopButton != null) stopButton.IsEnabled = false; AppendLog("Mindcraft 已退出。"); }); };
            mindcraftProcess.Start();
            lastMindcraftStartAt = DateTime.Now;
            mindcraftProcess.BeginOutputReadLine();
            mindcraftProcess.BeginErrorReadLine();
            statusText.Text = "正在运行";
            startButton.IsEnabled = false;
            if (stopButton != null) stopButton.IsEnabled = false;
            AppendLog("Mindcraft 已启动。");
            await Task.Delay(3000);
            if (mindcraftProcess != null && !mindcraftProcess.HasExited && stopButton != null) stopButton.IsEnabled = true;
        }

        private async void StopClicked(object sender, RoutedEventArgs e)
        {
            if (DateTime.Now - lastMindcraftStartAt < TimeSpan.FromSeconds(3))
            {
                AppendLog("Mindcraft 刚启动，已忽略一次过早的停止请求。若要停止，请 3 秒后再点停止。");
                return;
            }
            if (stopButton != null) stopButton.IsEnabled = false;
            AppendLog("正在停止 Mindcraft 和所有 Bot...");
            bool shutdownRequested = false;
            try
            {
                string url = "http://localhost:" + mindserverPort + "/api/shutdown";
                string result = await PostJsonAsync(url, "{}", 3000);
                shutdownRequested = result.Contains("\"ok\":true");
                AppendLog(shutdownRequested ? "已通知 MindServer 停止。" : "MindServer 返回异常: " + Compact(result));
            }
            catch (Exception ex)
            {
                AppendLog("无法连接 MindServer 停止接口，改用本地进程兜底停止: " + FriendlyError(ex.Message));
            }

            try
            {
                await Task.Delay(shutdownRequested ? 1200 : 100);
                if (mindcraftProcess != null && !mindcraftProcess.HasExited)
                {
                    mindcraftProcess.Kill();
                    AppendLog("本地 Mindcraft 进程已强制结束。已有正常退出时会自动忽略这一步。");
                }
                else if (!shutdownRequested)
                {
                    AppendLog("没有找到由启动器启动的 Mindcraft 进程。若网页仍显示运行，请关闭旧网页服务后重试。");
                }
            }
            catch (Exception ex)
            {
                AppendLog("停止失败: " + FriendlyError(ex.Message));
            }
            finally
            {
                if (stopButton != null) stopButton.IsEnabled = false;
                StopManagedProcess(ref voiceProcess, "TTS 语音服务");
                StopManagedProcess(ref asrProcess, "ASR 麦克风识别");
            }
        }

        private void OpenConsoleClicked(object sender, RoutedEventArgs e)
        {
            OpenConsole();
        }

        private void OpenConsole()
        {
            try { Process.Start(new ProcessStartInfo { FileName = "http://localhost:" + mindserverPort + "/", UseShellExecute = true }); }
            catch (Exception ex) { AppendLog("打开控制台失败: " + ex.Message); }
        }

        private void SetConnectionReady(bool ready)
        {
            connectionReady = ready;
            if (startButton != null) startButton.IsEnabled = ready && (mindcraftProcess == null || mindcraftProcess.HasExited);
        }

        private string Quote(string value)
        {
            return "\"" + (value ?? "").Replace("\"", "\\\"") + "\"";
        }

        private string ExtractJsonError(string output)
        {
            try
            {
                var dict = json.Deserialize<Dictionary<string, object>>(output);
                if (dict != null && dict.ContainsKey("error")) return Convert.ToString(dict["error"]);
            }
            catch { }
            return output;
        }

        private async Task<string> GetTextUrlAsync(string url, int timeoutMs)
        {
            var req = (System.Net.HttpWebRequest)System.Net.WebRequest.Create(url);
            req.Timeout = timeoutMs;
            using (var res = (System.Net.HttpWebResponse)await req.GetResponseAsync())
            using (var stream = res.GetResponseStream())
            using (var reader = new StreamReader(stream, Encoding.UTF8))
                return await reader.ReadToEndAsync();
        }

        private async Task<string> PostJsonAsync(string url, string body, int timeoutMs)
        {
            var data = Encoding.UTF8.GetBytes(body);
            var req = (System.Net.HttpWebRequest)System.Net.WebRequest.Create(url);
            req.Method = "POST";
            req.ContentType = "application/json";
            req.Timeout = timeoutMs;
            req.ContentLength = data.Length;
            using (var stream = await req.GetRequestStreamAsync())
                await stream.WriteAsync(data, 0, data.Length);
            using (var res = (System.Net.HttpWebResponse)await req.GetResponseAsync())
            using (var stream = res.GetResponseStream())
            using (var reader = new StreamReader(stream, Encoding.UTF8))
                return await reader.ReadToEndAsync();
        }

        private string Compact(string text)
        {
            text = (text ?? "").Replace("\r", " ").Replace("\n", " ");
            return text.Length > 300 ? text.Substring(0, 300) + "..." : text;
        }

        private string JsonEscape(string value)
        {
            return (value ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"");
        }
        private Task<string> RunNodeAsync(string arguments, string stdin, int timeoutMs)
        {
            return Task.Factory.StartNew(delegate
            {
                var psi = new ProcessStartInfo
                {
                    FileName = nodePath,
                    Arguments = arguments,
                    WorkingDirectory = appDir,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    RedirectStandardInput = stdin != null,
                    CreateNoWindow = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    StandardErrorEncoding = Encoding.UTF8
                };
                using (var process = new Process { StartInfo = psi })
                {
                    process.Start();
                    if (stdin != null)
                    {
                        process.StandardInput.Write(stdin);
                        process.StandardInput.Close();
                    }
                    string output = process.StandardOutput.ReadToEnd();
                    string error = process.StandardError.ReadToEnd();
                    if (!process.WaitForExit(timeoutMs))
                    {
                        try { process.Kill(); } catch { }
                        throw new TimeoutException("Node command timed out.");
                    }
                    if (process.ExitCode != 0)
                        throw new Exception(String.IsNullOrWhiteSpace(error) ? output : error);
                    return output.Trim();
                }
            });
        }

        private Task<string> RunPowerShellOnceAsync(string scriptName, int timeoutMs)
        {
            return Task.Factory.StartNew(delegate
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "powershell",
                    Arguments = "-NoProfile -ExecutionPolicy Bypass -File " + Quote(Path.Combine(appDir, scriptName)),
                    WorkingDirectory = appDir,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    StandardErrorEncoding = Encoding.UTF8
                };
                using (var process = new Process { StartInfo = psi })
                {
                    process.Start();
                    string output = process.StandardOutput.ReadToEnd();
                    string error = process.StandardError.ReadToEnd();
                    if (!process.WaitForExit(timeoutMs))
                    {
                        try { process.Kill(); } catch { }
                        throw new TimeoutException("PowerShell command timed out.");
                    }
                    if (process.ExitCode != 0)
                        throw new Exception(String.IsNullOrWhiteSpace(error) ? output : error);
                    return output.Trim();
                }
            });
        }

        private Task<string> RunPythonOnceAsync(string arguments, int timeoutMs)
        {
            return Task.Factory.StartNew(delegate
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "python",
                    Arguments = arguments,
                    WorkingDirectory = appDir,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    StandardErrorEncoding = Encoding.UTF8
                };
                psi.EnvironmentVariables["PYTHONUTF8"] = "1";
                psi.EnvironmentVariables["PYTHONIOENCODING"] = "utf-8";
                using (var process = new Process { StartInfo = psi })
                {
                    process.Start();
                    string output = process.StandardOutput.ReadToEnd();
                    string error = process.StandardError.ReadToEnd();
                    if (!process.WaitForExit(timeoutMs))
                    {
                        try { process.Kill(); } catch { }
                        throw new TimeoutException("Python command timed out.");
                    }
                    if (process.ExitCode != 0)
                        throw new Exception(String.IsNullOrWhiteSpace(error) ? output : error);
                    return output.Trim();
                }
            });
        }
        private bool IsNoisyLogLine(string text)
        {
            if (String.IsNullOrEmpty(text)) return false;
            return text.Contains("[BP-DIAG]")
                || text.Contains("[MessageRouter] ignored chat")
                || text.Contains("[CROSSHAIR]")
                || text.Contains("/scan/entities?")
                || text.Contains("GET /state status=200");
        }

        private void AppendLog(string text)
        {
            if (!Dispatcher.CheckAccess())
            {
                Dispatcher.BeginInvoke((Action)delegate { AppendLog(text); });
                return;
            }
            if (logBox != null)
            {
                bool verbose = showVerboseLog != null && showVerboseLog.IsChecked == true;
                if (!verbose && IsNoisyLogLine(text)) return;
                logBox.AppendText("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + text + Environment.NewLine);
                if (autoScrollLog == null || autoScrollLog.IsChecked == true)
                    logBox.ScrollToEnd();
            }
        }
    }
}


















