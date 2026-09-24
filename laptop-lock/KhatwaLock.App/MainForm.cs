using System.Drawing;
using KhatwaLock.Core;

namespace KhatwaLock.App;

/// <summary>
/// The friendly window: pair once, then paste the phone's lock code to lock the laptop.
/// (Unlocking happens on the full-screen LockForm.)
/// </summary>
internal sealed class MainForm : Form
{
    private static readonly Color Bg = Color.FromArgb(15, 17, 21);
    private static readonly Color Card = Color.FromArgb(23, 26, 33);
    private static readonly Color Field = Color.FromArgb(31, 35, 44);
    private static readonly Color Fg = Color.FromArgb(233, 236, 241);
    private static readonly Color MutedFg = Color.FromArgb(167, 174, 187);
    private static readonly Color Green = Color.FromArgb(123, 211, 137);
    private static readonly Color GreenFg = Color.FromArgb(11, 26, 16);
    private static readonly Color Red = Color.FromArgb(255, 122, 122);

    private LockConfig _config;
    private readonly LockStateStore _state;
    private readonly SurrenderLog _surrenders;
    private Strings _t;

    private readonly Label _title = new();
    private readonly Button _lang = new();
    private readonly Label _heading = new();
    private readonly Label _hint = new();
    private readonly TextBox _input = new();
    private readonly Button _action = new();
    private readonly Label _status = new();
    private readonly Button _startup = new();
    private readonly Button _unpair = new();
    private readonly Button _log = new();
    private readonly Button _showLock = new();
    private readonly Label _footer = new();

    public MainForm(LockConfig config, LockStateStore state, SurrenderLog surrenders)
    {
        _config = config;
        _state = state;
        _surrenders = surrenders;
        _t = Strings.For(config.IsArabic);

        Text = "khatwa";
        Size = new Size(640, 620);
        MinimumSize = new Size(560, 560);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Bg;
        ForeColor = Fg;
        Font = new Font("Segoe UI", 12f);
        try { Icon = Icon.ExtractAssociatedIcon(Program.ExePath); } catch { /* no icon: fine */ }

        BuildLayout();
        Render();
    }

    private void BuildLayout()
    {
        var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, Padding = new Padding(28, 20, 28, 16), BackColor = Bg };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        // header: title + language toggle
        var header = new TableLayoutPanel { ColumnCount = 2, AutoSize = true, Dock = DockStyle.Top, Margin = new Padding(0, 0, 0, 14) };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        _title.Font = new Font("Segoe UI", 20f, FontStyle.Bold);
        _title.AutoSize = true;
        _title.Anchor = AnchorStyles.Left;
        _lang.AutoSize = true;
        _lang.FlatStyle = FlatStyle.Flat;
        _lang.FlatAppearance.BorderColor = Color.FromArgb(44, 49, 60);
        _lang.ForeColor = MutedFg;
        _lang.BackColor = Bg;
        _lang.Font = new Font("Segoe UI", 10.5f);
        _lang.Anchor = AnchorStyles.Right;
        _lang.Click += (_, _) => ToggleLanguage();
        header.Controls.Add(_title, 0, 0);
        header.Controls.Add(_lang, 1, 0);

        // card: heading, hint, input, action, status
        var card = new TableLayoutPanel { ColumnCount = 1, AutoSize = true, Dock = DockStyle.Top, BackColor = Card, Padding = new Padding(22, 18, 22, 18), Margin = new Padding(0, 0, 0, 14) };
        card.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        _heading.Font = new Font("Segoe UI", 15f, FontStyle.Bold);
        _heading.AutoSize = true;
        _heading.MaximumSize = new Size(540, 0);
        _heading.Margin = new Padding(0, 0, 0, 8);
        _hint.Font = new Font("Segoe UI", 11.5f);
        _hint.ForeColor = MutedFg;
        _hint.AutoSize = true;
        _hint.MaximumSize = new Size(540, 0);
        _hint.Margin = new Padding(0, 0, 0, 14);
        _input.Font = new Font("Consolas", 24f, FontStyle.Bold);
        _input.BackColor = Field;
        _input.ForeColor = Fg;
        _input.BorderStyle = BorderStyle.FixedSingle;
        _input.TextAlign = HorizontalAlignment.Center;
        _input.RightToLeft = RightToLeft.No;
        _input.Dock = DockStyle.Top;
        _input.Margin = new Padding(0, 0, 0, 12);
        _input.CharacterCasing = CharacterCasing.Upper;
        _input.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; DoAction(); } };
        _action.Font = new Font("Segoe UI", 14f, FontStyle.Bold);
        _action.Height = 56;
        _action.Dock = DockStyle.Top;
        _action.BackColor = Green;
        _action.ForeColor = GreenFg;
        _action.FlatStyle = FlatStyle.Flat;
        _action.FlatAppearance.BorderSize = 0;
        _action.Margin = new Padding(0, 0, 0, 10);
        _action.Click += (_, _) => DoAction();
        _status.Font = new Font("Segoe UI", 11f);
        _status.AutoSize = true;
        _status.MaximumSize = new Size(540, 0);
        _status.ForeColor = Red;
        _showLock.Font = new Font("Segoe UI", 11f);
        _showLock.AutoSize = true;
        _showLock.FlatStyle = FlatStyle.Flat;
        _showLock.ForeColor = Fg;
        _showLock.BackColor = Card;
        _showLock.Visible = false;
        _showLock.Click += (_, _) => Program.ShowLockScreen(_config, _state, _surrenders);
        card.Controls.Add(_heading);
        card.Controls.Add(_hint);
        card.Controls.Add(_input);
        card.Controls.Add(_action);
        card.Controls.Add(_status);
        card.Controls.Add(_showLock);

        // secondary actions
        var actions = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Top, FlowDirection = FlowDirection.LeftToRight, WrapContents = true, Margin = new Padding(0, 0, 0, 10) };
        foreach (var b in new[] { _startup, _unpair, _log })
        {
            b.AutoSize = true;
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderColor = Color.FromArgb(44, 49, 60);
            b.ForeColor = MutedFg;
            b.BackColor = Bg;
            b.Font = new Font("Segoe UI", 10.5f);
            b.Margin = new Padding(0, 0, 8, 8);
            actions.Controls.Add(b);
        }
        _startup.Click += (_, _) => { if (Startup.IsEnabled()) Startup.Disable(); else Startup.Enable(); Render(); };
        _unpair.Click += (_, _) => Unpair();
        _log.Click += (_, _) => new LogForm(_surrenders, _t).ShowDialog(this);

        _footer.Font = new Font("Segoe UI", 9.5f);
        _footer.ForeColor = Color.FromArgb(90, 98, 112);
        _footer.AutoSize = true;
        _footer.Dock = DockStyle.Bottom;

        root.Controls.Add(header);
        root.Controls.Add(card);
        root.Controls.Add(actions);
        root.Controls.Add(_footer);
        Controls.Add(root);
    }

    private void Render()
    {
        _t = Strings.For(_config.IsArabic);
        RightToLeft = _t.Arabic ? RightToLeft.Yes : RightToLeft.No;
        RightToLeftLayout = _t.Arabic;
        Text = _t.AppTitle;
        _title.Text = _t.AppTitle;
        _lang.Text = _t.LanguageToggle;
        _footer.Text = _t.Footer;
        _log.Text = _t.ShowLog;
        _startup.Text = Startup.IsEnabled() ? _t.StartupOn : _t.StartupOff;
        _unpair.Text = _t.Unpair;
        _status.Text = string.Empty;
        _input.Text = string.Empty;

        if (!_config.HasSecret)
        {
            _heading.Text = _t.PairTitle;
            _hint.Text = _t.PairHint;
            _input.MaxLength = 19;
            _input.PlaceholderText = _t.PairPlaceholder;
            _input.Visible = true;
            _action.Text = _t.PairButton;
            _action.Visible = true;
            _showLock.Visible = false;
            _unpair.Visible = false;
            _startup.Visible = false;
        }
        else if (_state.IsLocked)
        {
            _heading.Text = _t.LockedNowTitle;
            _hint.Text = _t.LockedNowHint;
            _input.Visible = false;
            _action.Visible = false;
            _showLock.Text = _t.OpenLockScreen;
            _showLock.Visible = true;
            _unpair.Visible = false;
            _startup.Visible = true;
        }
        else
        {
            _heading.Text = _t.PairedTitle;
            _hint.Text = _t.LockHint;
            _input.MaxLength = 9;
            _input.PlaceholderText = _t.LockPlaceholder;
            _input.Visible = true;
            _action.Text = _t.LockButton;
            _action.Visible = true;
            _showLock.Visible = false;
            _unpair.Visible = true;
            _startup.Visible = true;
        }
        _input.Focus();
    }

    private void DoAction()
    {
        if (!_config.HasSecret) Pair(); else Lock();
    }

    private void Pair()
    {
        var code = ChallengeCodes.Normalize(_input.Text);
        if (!ChallengeCodes.IsSecret(code))
        {
            _status.ForeColor = Red;
            _status.Text = _t.PairBad;
            return;
        }
        _config.Secret = code;
        _config.Save(KhatwaPaths.ConfigPath);
        Startup.Enable();
        Log.Write("paired");
        Render();
    }

    private void Lock()
    {
        var id = ChallengeCodes.VerifyLockCode(_config.NormalizedSecret, _input.Text);
        if (id == null)
        {
            _status.ForeColor = Red;
            _status.Text = _t.LockBad;
            _input.SelectAll();
            return;
        }
        _state.MarkLocked(id);
        Log.Write($"locked by lock code; challenge {id}");
        _status.ForeColor = Green;
        _status.Text = _t.LockOk;
        Startup.StartWatchdogNow();
        Program.ShowLockScreen(_config, _state, _surrenders);
        Render();
    }

    private void Unpair()
    {
        if (MessageBox.Show(this, _t.UnpairConfirm, _t.AppTitle, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
        _config.Secret = string.Empty;
        _config.Save(KhatwaPaths.ConfigPath);
        _state.Clear();
        Startup.Disable();
        Log.Write("unpaired");
        Render();
    }

    private void ToggleLanguage()
    {
        _config.Language = _config.IsArabic ? "en" : "ar";
        _config.Save(KhatwaPaths.ConfigPath);
        Render();
    }

    /// <summary>Called by the lock screen when it closes so the main window reflects the new state.</summary>
    public void Reload(LockConfig config)
    {
        _config = config;
        Render();
    }
}
