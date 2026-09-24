using System.Drawing;
using KhatwaLock.Core;

namespace KhatwaLock.App;

/// <summary>
/// Full-screen, topmost lock window: "walk to unlock this laptop". Accepts the unlock code of the
/// challenge that locked it. Emergency exit = wait + phrase + confirm, logged as a surrender.
/// Extra monitors are covered by blank topmost forms.
/// </summary>
internal sealed class LockForm : Form
{
    private readonly LockConfig _config;
    private readonly LockStateStore _state;
    private readonly SurrenderLog _surrenders;
    private readonly Strings _t;
    private readonly bool _smokeTest;
    private readonly KeyboardHook? _hook;
    private readonly System.Windows.Forms.Timer _guard = new() { Interval = 700 };
    private readonly System.Windows.Forms.Timer _countdown = new() { Interval = 1000 };
    private readonly List<Form> _covers = new();
    private bool _allowClose;
    private int _secondsLeft;
    private int _wrongAttempts;

    private readonly Label _title = new();
    private readonly Label _subtitle = new();
    private readonly TextBox _code = new();
    private readonly Button _unlock = new();
    private readonly Label _status = new();
    private readonly Button _emergency = new();
    private readonly Panel _emergencyPanel = new();
    private readonly Label _emergencyInfo = new();
    private readonly TextBox _phrase = new();
    private readonly Button _continue = new();
    private readonly Button _confirm = new();
    private readonly Button _cancelEmergency = new();
    private readonly Label _footer = new();

    public bool SmokeWrongRejected { get; private set; }
    public bool SmokeRightAccepted { get; private set; }

    public LockForm(LockConfig config, LockStateStore state, SurrenderLog surrenders, bool smokeTest = false)
    {
        _config = config;
        _state = state;
        _surrenders = surrenders;
        _smokeTest = smokeTest;
        _t = Strings.For(config.IsArabic);

        Text = "khatwa";
        FormBorderStyle = FormBorderStyle.None;
        WindowState = FormWindowState.Maximized;
        StartPosition = FormStartPosition.Manual;
        Bounds = Screen.PrimaryScreen?.Bounds ?? new Rectangle(0, 0, 1280, 720);
        TopMost = true;
        ShowInTaskbar = false;
        ControlBox = false;
        KeyPreview = true;
        BackColor = Color.FromArgb(15, 17, 21);
        ForeColor = Color.FromArgb(233, 236, 241);
        RightToLeft = _t.Arabic ? RightToLeft.Yes : RightToLeft.No;
        RightToLeftLayout = _t.Arabic;
        Font = new Font("Segoe UI", 14f);

        BuildLayout();
        if (!smokeTest)
        {
            try { _hook = new KeyboardHook(); } catch { _hook = null; }
        }
        _guard.Tick += (_, _) => Guard();
        _countdown.Tick += (_, _) => CountdownTick();
        Shown += (_, _) =>
        {
            CoverOtherScreens();
            Activate();
            _code.Focus();
            _guard.Start();
            if (_smokeTest) RunSmokeChecks();
        };
        FormClosing += (_, e) =>
        {
            // Never block Windows from restarting or shutting down (the lock comes back after
            // sign-in anyway while it is still active). Alt+F4 or a polite "End task": stay.
            if (!_allowClose && e.CloseReason != CloseReason.WindowsShutDown)
            {
                e.Cancel = true;
            }
        };
    }

    private void BuildLayout()
    {
        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 8,
            Padding = new Padding(40),
            BackColor = BackColor,
        };
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 18));
        for (var i = 0; i < 6; i++) panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        _title.Text = _t.LockScreenTitle;
        _title.Font = new Font("Segoe UI", 30f, FontStyle.Bold);
        _title.AutoSize = true;
        _title.Anchor = AnchorStyles.None;
        _title.TextAlign = ContentAlignment.MiddleCenter;

        _subtitle.Text = _t.LockScreenSubtitle;
        _subtitle.Font = new Font("Segoe UI", 14f);
        _subtitle.ForeColor = Color.FromArgb(167, 174, 187);
        _subtitle.AutoSize = true;
        _subtitle.Anchor = AnchorStyles.None;
        _subtitle.MaximumSize = new Size(1000, 0);
        _subtitle.TextAlign = ContentAlignment.MiddleCenter;

        var codeRow = new FlowLayoutPanel { AutoSize = true, Anchor = AnchorStyles.None, FlowDirection = FlowDirection.LeftToRight, WrapContents = false, Margin = new Padding(0, 30, 0, 10) };
        _code.Font = new Font("Consolas", 36f, FontStyle.Bold);
        _code.MaxLength = 7;
        _code.Width = 300;
        _code.TextAlign = HorizontalAlignment.Center;
        _code.BackColor = Color.FromArgb(31, 35, 44);
        _code.ForeColor = ForeColor;
        _code.BorderStyle = BorderStyle.FixedSingle;
        _code.RightToLeft = RightToLeft.No;
        _code.PlaceholderText = "000 000";
        _code.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; TryUnlock(); } };
        // Checked as soon as the sixth digit is typed (not while the retry delay is running).
        _code.TextChanged += (_, _) =>
        {
            if (_unlock.Enabled && ChallengeCodes.Normalize(_code.Text).Length == ChallengeCodes.CodeLength) TryUnlock();
        };
        _code.Name = "code";
        _unlock.Text = _t.UnlockButton;
        _unlock.Font = new Font("Segoe UI", 18f, FontStyle.Bold);
        _unlock.Size = new Size(170, 72);
        _unlock.BackColor = Color.FromArgb(123, 211, 137);
        _unlock.ForeColor = Color.FromArgb(11, 26, 16);
        _unlock.FlatStyle = FlatStyle.Flat;
        _unlock.FlatAppearance.BorderSize = 0;
        _unlock.Margin = new Padding(16, 0, 0, 0);
        _unlock.Click += (_, _) => TryUnlock();
        _unlock.Name = "unlock";
        codeRow.Controls.Add(_code);
        codeRow.Controls.Add(_unlock);

        _status.Text = string.Empty;
        _status.Font = new Font("Segoe UI", 13f);
        _status.ForeColor = Color.FromArgb(255, 122, 122);
        _status.AutoSize = true;
        _status.Anchor = AnchorStyles.None;
        _status.Name = "status";

        _emergency.Text = _t.Emergency;
        _emergency.Font = new Font("Segoe UI", 12f);
        _emergency.AutoSize = true;
        _emergency.FlatStyle = FlatStyle.Flat;
        _emergency.ForeColor = Color.FromArgb(167, 174, 187);
        _emergency.BackColor = BackColor;
        _emergency.FlatAppearance.BorderColor = Color.FromArgb(44, 49, 60);
        _emergency.Anchor = AnchorStyles.None;
        _emergency.Margin = new Padding(0, 30, 0, 0);
        _emergency.Click += (_, _) => StartEmergency();
        _emergency.Name = "emergency";

        _emergencyPanel.Visible = false;
        _emergencyPanel.AutoSize = true;
        _emergencyPanel.Anchor = AnchorStyles.None;
        _emergencyPanel.BackColor = Color.FromArgb(31, 35, 44);
        _emergencyPanel.Padding = new Padding(24);
        var ep = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Dock = DockStyle.Fill };
        _emergencyInfo.AutoSize = true;
        _emergencyInfo.MaximumSize = new Size(900, 0);
        _emergencyInfo.Font = new Font("Segoe UI", 14f);
        _phrase.Width = 900;
        _phrase.Font = new Font("Segoe UI", 14f);
        _phrase.Multiline = true;
        _phrase.Height = 70;
        _phrase.Visible = false;
        _phrase.Name = "phrase";
        _phrase.TextChanged += (_, _) => _continue.Enabled = LockDecision.PhraseMatches(_config.EmergencyPhrase, _phrase.Text);
        _continue.Text = _t.Continue;
        _continue.Enabled = false;
        _continue.Visible = false;
        _continue.AutoSize = true;
        _continue.Font = new Font("Segoe UI", 13f, FontStyle.Bold);
        _continue.BackColor = Color.FromArgb(192, 57, 43);
        _continue.ForeColor = Color.White;
        _continue.FlatStyle = FlatStyle.Flat;
        _continue.Click += (_, _) => { _continue.Visible = false; _confirm.Visible = true; };
        _confirm.Text = _t.ConfirmSurrender;
        _confirm.Visible = false;
        _confirm.AutoSize = true;
        _confirm.Font = new Font("Segoe UI", 13f, FontStyle.Bold);
        _confirm.BackColor = Color.FromArgb(192, 57, 43);
        _confirm.ForeColor = Color.White;
        _confirm.FlatStyle = FlatStyle.Flat;
        _confirm.Click += (_, _) => Surrender();
        _cancelEmergency.Text = _t.BackToWalk;
        _cancelEmergency.AutoSize = true;
        _cancelEmergency.Font = new Font("Segoe UI", 12f);
        _cancelEmergency.FlatStyle = FlatStyle.Flat;
        _cancelEmergency.ForeColor = ForeColor;
        _cancelEmergency.Click += (_, _) => CancelEmergency();
        ep.Controls.Add(_emergencyInfo);
        ep.Controls.Add(_phrase);
        ep.Controls.Add(_continue);
        ep.Controls.Add(_confirm);
        ep.Controls.Add(_cancelEmergency);
        _emergencyPanel.Controls.Add(ep);

        _footer.Text = _t.LockFooter;
        _footer.Font = new Font("Segoe UI", 10f);
        _footer.ForeColor = Color.FromArgb(90, 98, 112);
        _footer.AutoSize = true;
        _footer.Anchor = AnchorStyles.Bottom;
        _footer.Margin = new Padding(0, 40, 0, 0);

        panel.Controls.Add(new Panel { Height = 1 }, 0, 0);
        panel.Controls.Add(_title, 0, 1);
        panel.Controls.Add(_subtitle, 0, 2);
        panel.Controls.Add(codeRow, 0, 3);
        panel.Controls.Add(_status, 0, 4);
        panel.Controls.Add(_emergency, 0, 5);
        panel.Controls.Add(_emergencyPanel, 0, 6);
        panel.Controls.Add(_footer, 0, 7);
        Controls.Add(panel);
    }

    private void CoverOtherScreens()
    {
        foreach (var screen in Screen.AllScreens)
        {
            if (screen.Primary) continue;
            var cover = new Form
            {
                FormBorderStyle = FormBorderStyle.None,
                StartPosition = FormStartPosition.Manual,
                Bounds = screen.Bounds,
                TopMost = true,
                ShowInTaskbar = false,
                BackColor = BackColor,
                Text = "khatwa",
            };
            cover.FormClosing += (_, e) => { if (!_allowClose && e.CloseReason != CloseReason.WindowsShutDown) e.Cancel = true; };
            cover.Show();
            _covers.Add(cover);
        }
    }

    private void Guard()
    {
        if (_allowClose) return;
        try
        {
            if (!TopMost) TopMost = true;
            if (WindowState != FormWindowState.Maximized) WindowState = FormWindowState.Maximized;
            var b = Screen.PrimaryScreen?.Bounds;
            if (b != null && Bounds != b.Value) Bounds = b.Value;
            if (Form.ActiveForm != this && !_smokeTest) Activate();
            foreach (var c in _covers) { if (!c.TopMost) c.TopMost = true; }
        }
        catch { /* keep guarding */ }
    }

    private void TryUnlock()
    {
        var counter = _state.Counter;
        if (ChallengeCodes.VerifyUnlockCode(_config.NormalizedSecret, counter, _code.Text))
        {
            _state.MarkUnlocked("code");
            Log.Write($"unlock code accepted for challenge {counter}");
            _status.ForeColor = Color.FromArgb(123, 211, 137);
            _status.Text = _t.UnlockOk;
            ForceClose();
            return;
        }
        _wrongAttempts++;
        _status.ForeColor = Color.FromArgb(255, 122, 122);
        _status.Text = _wrongAttempts < 3 ? _t.UnlockBad : string.Format(_t.UnlockBadMany, _wrongAttempts);
        // Clear it (rather than select it) so the same wrong code is not submitted again automatically.
        _unlock.Enabled = false;
        _code.Text = string.Empty;
        // Slow down guessing a little.
        var t = new System.Windows.Forms.Timer { Interval = Math.Min(5000, 800 * _wrongAttempts) };
        t.Tick += (_, _) => { t.Stop(); t.Dispose(); _unlock.Enabled = true; _code.Focus(); };
        t.Start();
    }

    private void StartEmergency()
    {
        _emergency.Visible = false;
        _emergencyPanel.Visible = true;
        _secondsLeft = _config.EmergencyWaitSeconds;
        _phrase.Visible = false; _continue.Visible = false; _confirm.Visible = false;
        _phrase.Text = string.Empty;
        UpdateCountdownText();
        if (_secondsLeft <= 0) ShowPhrase(); else _countdown.Start();
    }

    private void CountdownTick()
    {
        _secondsLeft--;
        if (_secondsLeft <= 0) { _countdown.Stop(); ShowPhrase(); }
        else UpdateCountdownText();
    }

    private void UpdateCountdownText() => _emergencyInfo.Text = string.Format(_t.EmergencyWait, _secondsLeft);

    private void ShowPhrase()
    {
        _emergencyInfo.Text = string.Format(_t.EmergencyType, _config.EmergencyPhrase);
        _phrase.Visible = true;
        _continue.Visible = true;
        _phrase.Focus();
    }

    private void CancelEmergency()
    {
        _countdown.Stop();
        _emergencyPanel.Visible = false;
        _emergency.Visible = true;
        _code.Focus();
    }

    private void Surrender()
    {
        _surrenders.Append(DateTime.Now, "emergency unlock from the lock screen");
        _state.MarkUnlocked("surrender");
        Log.Write("SURRENDER recorded; unlocked");
        ForceClose();
    }

    public void ForceClose()
    {
        _allowClose = true;
        _guard.Stop();
        _countdown.Stop();
        _hook?.Dispose();
        foreach (var c in _covers) { try { c.Close(); } catch { } }
        Close();
    }

    private void RunSmokeChecks()
    {
        var counter = _state.Counter > 0 ? _state.Counter : 1;
        var right = ChallengeCodes.UnlockCode(_config.NormalizedSecret, counter);
        var wrong = (right[0] == '0' ? "1" : "0") + right[1..];
        _code.Text = wrong;
        TryUnlock();
        SmokeWrongRejected = _state.IsLocked && !_allowClose;
        _unlock.Enabled = true;
        _code.Text = right;
        TryUnlock();
        SmokeRightAccepted = !_state.IsLocked;
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Escape) { e.Handled = true; return; }
        base.OnKeyDown(e);
    }
}
