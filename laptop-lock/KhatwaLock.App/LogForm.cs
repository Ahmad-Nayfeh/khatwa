using System.Drawing;
using KhatwaLock.Core;

namespace KhatwaLock.App;

/// <summary>Small window listing every recorded surrender (date, time, note).</summary>
internal sealed class LogForm : Form
{
    public LogForm(SurrenderLog log)
    {
        Text = "خطوة · سجل الاستسلامات";
        Size = new Size(640, 480);
        StartPosition = FormStartPosition.CenterScreen;
        RightToLeft = RightToLeft.Yes;
        RightToLeftLayout = true;
        BackColor = Color.FromArgb(15, 17, 21);
        ForeColor = Color.FromArgb(233, 236, 241);
        Font = new Font("Segoe UI", 12f);

        var entries = log.Read();
        var header = new Label
        {
            Text = entries.Count == 0 ? "لا توجد استسلامات مسجّلة." : $"عدد الاستسلامات: {entries.Count}",
            Dock = DockStyle.Top,
            Height = 48,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Segoe UI", 14f, FontStyle.Bold),
        };
        var list = new ListView
        {
            Dock = DockStyle.Fill,
            View = View.Details,
            FullRowSelect = true,
            BackColor = Color.FromArgb(23, 26, 33),
            ForeColor = ForeColor,
            BorderStyle = BorderStyle.None,
        };
        list.Columns.Add("التاريخ", 140);
        list.Columns.Add("الوقت", 140);
        list.Columns.Add("ملاحظة", 320);
        foreach (var e in entries.AsEnumerable().Reverse())
        {
            var time = DateTime.TryParse(e.At, out var dt) ? dt.ToString("HH:mm") : e.At;
            list.Items.Add(new ListViewItem(new[] { e.Date, time, e.Note }));
        }
        Controls.Add(list);
        Controls.Add(header);
    }
}
