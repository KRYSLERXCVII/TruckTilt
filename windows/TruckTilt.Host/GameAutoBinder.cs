using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

namespace TruckTilt.Host;

/// <summary>
/// Seamlessly binds TruckTilt's DirectInput device into the active ETS2/ATS
/// controls.sii profile. It never asks the user to choose ETS2 or ATS.
/// </summary>
internal static class GameAutoBinder
{
    private const string TruckTiltDeviceName = "TruckTilt Virtual Steering Wheel";

    private static readonly string[] GameProcesses =
    {
        "eurotrucks2",
        "amtrucks"
    };

    private static readonly Dictionary<string, string> GameDisplayNames =
        new(StringComparer.OrdinalIgnoreCase)
        {
            ["eurotrucks2"] = "ETS2",
            ["amtrucks"] = "ATS"
        };

    private static readonly (int Button, string Mix)[] ButtonBindings =
    {
        (1,  "lblinker"),
        (2,  "rblinker"),
        (3,  "flasher4way"),
        (4,  "light"),
        (5,  "hblight"),
        (6,  "horn"),
        (7,  "airhorn"),
        (8,  "wippers"),
        // Wiper +/- are mapped to explicit wiper stages when those mixes exist.
        (9,  "wipers2"),
        (10, "wipers1"),
        (11, "motorbrake"),
        (12, "retarderup"),
        (13, "retarderdown"),
        (14, "cruiectrl"),
        (15, "cruiectrlinc"),
        (16, "cruiectrldec"),
        (17, "parkingbrake"),
        (18, "engine"),
        (19, "gearup"),
        (20, "geardown"),
        (21, "camcycle"),
        (22, "showhud"),
        (23, "navmap"),
        (24, "showmirrors"),
        (25, "beacon"),
        (26, "diflock"),
        (27, "attach"),
        // Button 28 (WARNING) is intentionally left untouched because SCS
        // does not expose a stable warning-light mix across ETS2/ATS versions.
        (29, "cmirrorsel"),
        (30, "fmirrorsel"),
        (31, "activate"),
        (32, "menu")
    };

    private static readonly Dictionary<string, string> AxisBindings =
        new(StringComparer.OrdinalIgnoreCase)
        {
            ["j_steer"] = "joy.x",
            ["j_throttle"] = "joy.rx",
            ["j_brake"] = "joy.ry",
            ["j_clutch"] = "joy.sl1"
        };

    private static readonly object Sync = new();
    private static string? _lastConfiguredFile;
    private static string? _lastConfiguredDevice;
    private static bool _running;

    public static void Start()
    {
        lock (Sync)
        {
            if (_running)
                return;

            _running = true;
        }

        _ = Task.Run(async () =>
        {
            while (true)
            {
                try
                {
                    ConfigureIfNeeded();
                }
                catch
                {
                    // Auto-binding must never be able to take down the host.
                }

                await Task.Delay(2000);
            }
        });
    }

    private static void ConfigureIfNeeded()
    {
        Process? game = GetRunningGame();
        if (game is null)
            return;

        string processName = game.ProcessName;
        string gameName = GameDisplayNames.TryGetValue(processName, out var display)
            ? display
            : processName;

        string? gameRoot = FindGameDocumentsRoot(processName);
        if (gameRoot is null)
            return;

        string? controlsPath = FindActiveControlsFile(gameRoot);
        if (controlsPath is null)
            return;

        string text;
        try
        {
            text = File.ReadAllText(controlsPath);
        }
        catch
        {
            return;
        }

        string? deviceId = FindTruckTiltDeviceId(gameRoot, text);
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            // The game has not exposed TruckTilt's DirectInput ID in the log yet.
            // Wait for the next poll rather than guessing a device ID.
            return;
        }

        string signature = controlsPath + "|" + deviceId;
        lock (Sync)
        {
            if (string.Equals(_lastConfiguredFile, controlsPath, StringComparison.OrdinalIgnoreCase) &&
                string.Equals(_lastConfiguredDevice, deviceId, StringComparison.Ordinal))
                return;
        }

        if (!ApplyBindings(controlsPath, text, deviceId, out int changed, out string backupPath))
            return;

        lock (Sync)
        {
            _lastConfiguredFile = controlsPath;
            _lastConfiguredDevice = deviceId;
        }

        Console.WriteLine(
            $"AUTO-BIND: {gameName} detected — TruckTilt configured ({changed} changes).");
        Console.WriteLine($"AUTO-BIND: Backup created: {Path.GetFileName(backupPath)}");
    }

    private static Process? GetRunningGame()
    {
        foreach (string name in GameProcesses)
        {
            try
            {
                Process[] processes = Process.GetProcessesByName(name);
                if (processes.Length > 0)
                    return processes[0];
            }
            catch
            {
                // Ignore and continue checking the other game.
            }
        }

        return null;
    }

    private static string? FindGameDocumentsRoot(string processName)
    {
        string documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);

        string folder = processName.Equals("amtrucks", StringComparison.OrdinalIgnoreCase)
            ? "American Truck Simulator"
            : "Euro Truck Simulator 2";

        string root = Path.Combine(documents, folder);
        return Directory.Exists(root) ? root : null;
    }

    private static string? FindActiveControlsFile(string gameRoot)
    {
        // SCS uses either profiles or steam_profiles depending on profile/cloud setup.
        // The game log gives us the active encoded profile ID when available.
        string? profileId = FindActiveProfileId(gameRoot);

        if (!string.IsNullOrWhiteSpace(profileId))
        {
            foreach (string folderName in new[] { "profiles", "steam_profiles" })
            {
                string candidate = Path.Combine(gameRoot, folderName, profileId, "controls.sii");
                if (File.Exists(candidate))
                    return candidate;
            }
        }

        // Safe fallback: use the most recently modified non-backup controls.sii.
        // This matches the normal SCS profile workflow when the active profile
        // cannot be recovered from the current game log.
        var candidates = new List<FileInfo>();

        foreach (string folderName in new[] { "profiles", "steam_profiles" })
        {
            string folder = Path.Combine(gameRoot, folderName);
            if (!Directory.Exists(folder))
                continue;

            try
            {
                foreach (string path in Directory.EnumerateFiles(
                    folder, "controls.sii", SearchOption.AllDirectories))
                {
                    if (path.Contains(".bak", StringComparison.OrdinalIgnoreCase))
                        continue;

                    var info = new FileInfo(path);
                    if (info.DirectoryName?.Contains(".bak", StringComparison.OrdinalIgnoreCase) == true)
                        continue;

                    candidates.Add(info);
                }
            }
            catch
            {
                // Continue with what was found.
            }
        }

        return candidates
            .OrderByDescending(f => f.LastWriteTimeUtc)
            .FirstOrDefault()?.FullName;
    }

    private static string? FindActiveProfileId(string gameRoot)
    {
        string logPath = Path.Combine(gameRoot, "game.log.txt");
        if (!File.Exists(logPath))
            return null;

        try
        {
            string log = File.ReadAllText(logPath);

            // Example:
            // game /home/profiles/4B726973.../save/...
            // game /home/steam_profiles/....../save/...
            MatchCollection matches = Regex.Matches(
                log,
                @"game\s+/home/(?<folder>profiles|steam_profiles)/(?<id>[^/\s]+)/",
                RegexOptions.IgnoreCase);

            if (matches.Count == 0)
                return null;

            return matches[^1].Groups["id"].Value;
        }
        catch
        {
            return null;
        }
    }

    private static string? FindTruckTiltDeviceId(string gameRoot, string controlsText)
    {
        // If the profile already points at TruckTilt, use that ID immediately.
        Match existing = Regex.Match(
            controlsText,
            @"device\s+joy\s+`di8\.'(?<id>[^']+)'\s*`",
            RegexOptions.IgnoreCase);

        if (existing.Success &&
            existing.Groups["id"].Value.Contains("TruckTilt", StringComparison.OrdinalIgnoreCase))
        {
            return existing.Groups["id"].Value;
        }

        string logPath = Path.Combine(gameRoot, "game.log.txt");
        if (File.Exists(logPath))
        {
            try
            {
                string log = File.ReadAllText(logPath);

                // SCS documents this exact DirectInput identification mechanism:
                // [di8] Initializing device 'NAME' as 'DEVICE_ID'
                MatchCollection matches = Regex.Matches(
                    log,
                    @"Initializing device\s+'(?<name>[^']*TruckTilt[^']*)'\s+as\s+'(?<id>[^']+)'",
                    RegexOptions.IgnoreCase);

                if (matches.Count > 0)
                    return matches[^1].Groups["id"].Value;
            }
            catch
            {
                // Fall through to global_controls.sii.
            }
        }

        // Fallback for systems where the device was previously registered globally.
        string globalControls = Path.Combine(gameRoot, "global_controls.sii");
        if (File.Exists(globalControls))
        {
            try
            {
                string text = File.ReadAllText(globalControls);

                MatchCollection matches = Regex.Matches(
                    text,
                    @"display_names\[\d+\]:\s*""`di8\.'(?<id>[^']+)'\s*`\|[^""]*TruckTilt[^""]*""",
                    RegexOptions.IgnoreCase);

                if (matches.Count > 0)
                    return matches[^1].Groups["id"].Value;
            }
            catch
            {
                // No device ID available yet.
            }
        }

        return null;
    }

    private static bool ApplyBindings(
        string controlsPath,
        string original,
        string deviceId,
        out int changed,
        out string backupPath)
    {
        changed = 0;
        backupPath = string.Empty;

        string text = original;

        // SCS input identification is SYSTEM_ID.'DEVICE_ID'.INPUT_ID.
        // The profile's "joy" alias lets all other bindings use joy.x, joy.b1, etc.
        string deviceAlias = $"di8.'{deviceId}'";
        text = ReplaceDeviceAlias(text, deviceAlias, ref changed);

        foreach (var pair in AxisBindings)
            ReplaceInputAlias(text, pair.Key, pair.Value, ref changed, out text);

        foreach ((int button, string mix) in ButtonBindings)
            AddButtonToMix(ref text, mix, $"joy.b{button}", ref changed);

        // Separate axis configuration: TruckTilt has independent throttle/brake.
        ReplaceOrAddConstant(ref text, "c_jcombined", "0.000000", ref changed);

        if (text == original)
            return false;

        try
        {
            string timestamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
            backupPath = controlsPath + $".TruckTilt-{timestamp}.bak";

            File.Copy(controlsPath, backupPath, overwrite: false);

            string tempPath = controlsPath + ".trucktilt.tmp";
            File.WriteAllText(
                tempPath,
                text,
                new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));

            File.Move(tempPath, controlsPath, overwrite: true);
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static string ReplaceDeviceAlias(
        string text,
        string deviceAlias,
        ref int changed)
    {
        // Keep this as one verbatim regex string. The previous version mixed
        // escaped normal-string quotes with a verbatim string and produced
        // CS1056/CS1002 in GitHub Actions.
        string pattern =
            @"(?m)^(?<prefix>\s*config_lines\[\d+\]:\s*""device\s+joy\s+)`[^`]*`(?<suffix>""\s*)$";

        int localChanged = 0;

        string result = new Regex(pattern).Replace(
            text,
            m =>
            {
                string current = m.Value;
                string newLine = m.Groups["prefix"].Value + $"`{deviceAlias}`" +
                                  m.Groups["suffix"].Value;

                if (!current.Equals(newLine, StringComparison.Ordinal))
                    localChanged++;

                return newLine;
            },
            1);

        changed += localChanged;
        return result;
    }

    private static void ReplaceInputAlias(
        string currentText,
        string alias,
        string value,
        ref int changed,
        out string result)
    {
        string pattern =
            $@"(?m)^(?<prefix>\s*config_lines\[\d+\]:\s*""input\s+{Regex.Escape(alias)}\s+)`[^`]*`(?<suffix>""\s*)$";

        int localChanged = 0;

        result = new Regex(pattern).Replace(
            currentText,
            m =>
            {
                string newLine = m.Groups["prefix"].Value + $"`{value}`" +
                                 m.Groups["suffix"].Value;

                if (!m.Value.Equals(newLine, StringComparison.Ordinal))
                    localChanged++;

                return newLine;
            },
            1);

        changed += localChanged;
    }

    private static void AddButtonToMix(
        ref string text,
        string mixName,
        string input,
        ref int changed)
    {
        string pattern =
            $@"(?m)^(?<prefix>\s*config_lines\[\d+\]:\s*""mix\s+{Regex.Escape(mixName)}\s+)`(?<expr>[^`]*)`(?<suffix>""\s*)$";

        int localChanged = 0;

        text = new Regex(pattern).Replace(
            text,
            m =>
            {
                string expr = m.Groups["expr"].Value;

                if (expr.Contains(input + "?0", StringComparison.Ordinal))
                    return m.Value;

                string newExpr;
                int semanticIndex = expr.IndexOf("| semantical.", StringComparison.Ordinal);

                if (semanticIndex >= 0)
                {
                    string before = expr[..semanticIndex].TrimEnd();
                    string semantic = expr[semanticIndex..].TrimStart();
                    newExpr = string.IsNullOrWhiteSpace(before)
                        ? input + "?0 " + semantic
                        : before + " | " + input + "?0 " + semantic;
                }
                else if (string.IsNullOrWhiteSpace(expr))
                {
                    newExpr = input + "?0";
                }
                else
                {
                    newExpr = expr.TrimEnd() + " | " + input + "?0";
                }

                localChanged++;
                return m.Groups["prefix"].Value + "`" + newExpr + "`" +
                       m.Groups["suffix"].Value;
            },
            1);

        changed += localChanged;
    }

    private static void ReplaceOrAddConstant(
        ref string text,
        string name,
        string value,
        ref int changed)
    {
        string pattern =
            $@"(?m)^(?<prefix>\s*config_lines\[\d+\]:\s*""constant\s+{Regex.Escape(name)}\s+)[^""]*(?<suffix>""\s*)$";

        int localChanged = 0;

        string replaced = new Regex(pattern).Replace(
            text,
            m =>
            {
                string newLine = m.Groups["prefix"].Value + value +
                                  m.Groups["suffix"].Value;

                if (!m.Value.Equals(newLine, StringComparison.Ordinal))
                    localChanged++;

                return newLine;
            },
            1);

        changed += localChanged;

        if (replaced != text)
        {
            text = replaced;
            return;
        }

        // Do not invent a new config_lines index if the game version has no
        // c_jcombined constant. This keeps the file structure conservative.
    }
}
