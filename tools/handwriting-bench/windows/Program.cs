// Reads every InkML file of a directory with the handwriting recognizers of Windows
// (Windows.UI.Input.Inking.InkRecognizerContainer), from a desktop process, and writes a line a
// file: its name, the reading, the candidates of each word ("a/b|c/d"), the milliseconds.
//
//   dotnet run -c Release -- <directory of InkML> <readings.tsv>
//
// The language of a file is its annotation "language" (it, en): the recognizer whose name says it.
using System.Diagnostics;
using System.Globalization;
using System.Numerics;
using System.Text.RegularExpressions;
using Windows.Foundation;
using Windows.UI.Input.Inking;

static class Program
{
    // The ink of Windows wants a single-threaded apartment
    [STAThread]
    static int Main(string[] args)
    {
        try
        {
            var directory = args[0];
            var output = args[1];
            var container = new InkRecognizerContainer();
            var recognizers = container.GetRecognizers();
            Console.WriteLine("Recognizers: " + string.Join("; ", recognizers.Select(r => r.Name)));

            InkRecognizer? For(string language) => recognizers.FirstOrDefault(r =>
                language == "it" ? Regex.IsMatch(r.Name, "Ital", RegexOptions.IgnoreCase) :
                language == "en" ? Regex.IsMatch(r.Name, "English|Inglese", RegexOptions.IgnoreCase) && !Regex.IsMatch(r.Name, "UK|Regno", RegexOptions.IgnoreCase) :
                false) ?? (language == "en" ? recognizers.FirstOrDefault(r => Regex.IsMatch(r.Name, "English|Inglese", RegexOptions.IgnoreCase)) : null);

            float DipsPerMm = 96f / 25.4f;
            var lines = new List<string>();
            var times = new List<double>();
            foreach (var file in Directory.GetFiles(directory, "*.inkml").OrderBy(f => f))
            {
                var xml = File.ReadAllText(file);
                var language = Regex.Match(xml, "<annotation type=\"language\">([^<]*)</annotation>").Groups[1].Value;
                var recognizer = For(language);
                if (recognizer == null) { Console.WriteLine($"No recognizer for '{language}': {Path.GetFileName(file)}"); continue; }
                container.SetDefaultRecognizer(recognizer);
                var strokes = new InkStrokeContainer();
                var builder = new InkStrokeBuilder();
                foreach (Match trace in Regex.Matches(xml, "<trace[^>]*>([^<]*)</trace>"))
                {
                    var points = trace.Groups[1].Value.Split(',').Select(p => p.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries)).Where(v => v.Length >= 2).Select(v =>
                        new InkPoint(new Point(float.Parse(v[0], CultureInfo.InvariantCulture) * DipsPerMm, float.Parse(v[1], CultureInfo.InvariantCulture) * DipsPerMm),
                            0.5f, 0f, 0f, v.Length > 2 ? (ulong)(long.Parse(v[2], CultureInfo.InvariantCulture) * 1000) : 0UL)).ToList();
                    if (points.Count > 0) strokes.AddStroke(builder.CreateStrokeFromInkPoints(points, Matrix3x2.Identity, null, null));
                }
                var watch = Stopwatch.StartNew();
                var results = container.RecognizeAsync(strokes, InkRecognitionTarget.All).AsTask().GetAwaiter().GetResult();
                watch.Stop();
                var reading = string.Join(" ", results.Select(r => r.GetTextCandidates().FirstOrDefault() ?? ""));
                var candidates = string.Join("|", results.Select(r => string.Join("/", r.GetTextCandidates())));
                lines.Add($"{Path.GetFileName(file)}\t{reading.Replace('\t', ' ')}\t{candidates.Replace('\t', ' ')}\t{watch.Elapsed.TotalMilliseconds.ToString("F1", CultureInfo.InvariantCulture)}");
                times.Add(watch.Elapsed.TotalMilliseconds);
            }
            File.WriteAllLines(output, lines);
            Console.WriteLine($"{lines.Count} files read, median {times.OrderBy(t => t).ElementAtOrDefault(times.Count / 2):F1} ms");

            return 0;
        }
        catch (Exception e)
        {
            Console.Error.WriteLine(e);
            return 1;
        }
    }
}
