using System.IO.MemoryMappedFiles;
using System.Text;

namespace TruckTilt.Host;

/// <summary>
/// Optional ETS2/ATS telemetry reader for TruckTilt haptics.
/// Uses the Rev. 12 SCSTelemetry shared-memory layout.
/// Uses the Rev. 12 layout exactly; haptic events are sent silently to the connected phone.
/// </summary>
internal sealed class TelemetryHaptics : IDisposable
{
    private const string MapName = "Local\\SCSTelemetry";

    // Rev. 12 shared-memory offsets from scs-telemetry-common.hpp.
    private const int SdkActiveOffset = 0;
    private const int GearsOffset = 68;             // config_ui.gears
    private const int GearOffset = 500;             // truck_i.gear
    private const int EngineRpmMaxOffset = 740;     // config_f.engineRpmMax
    private const int EngineRpmOffset = 948;        // truck_f.engineRpm
    private const int WheelSubstanceOffset = 96;    // truck_ui.truck_wheelSubstance[0]
    private const int PoweredWheelsOffset = 1516;   // config_b.truckWheelPowered[0]
    private const int EngineEnabledOffset = 1576;   // truck_b.engineEnabled
    private const int WheelOnGroundOffset = 1590;   // truck_b.truck_wheelOnGround[0]
    private const int SubstanceNamesOffset = 4400;
    private const int SubstanceNameSize = 64;
    private const int PollMs = 50;
    // Give ETS2/ATS telemetry time to settle when entering the cockpit.
    // During this window we establish baselines but emit no telemetry-driven haptics.
    private const int StartupGraceMs = 2000;
    private const int EngineTransitionDebounceMs = 200;

    private readonly Action<string> _sendHaptic;
    private readonly CancellationTokenSource _cts = new();
    private Task? _loop;

    private MemoryMappedFile? _map;
    private MemoryMappedViewAccessor? _view;

    private bool _shiftLatched;
    private int _lastGear = int.MinValue;
    private bool _offRoadLatched;
    private int _lastRumbleIntensity = -1;
    private DateTime _lastRumbleSend = DateTime.MinValue;
    private bool _engineStateKnown;
    private bool _lastEngineEnabled;
    private bool _pendingEngineState;
    private DateTime _pendingEngineStateSince = DateTime.MinValue;
    private bool _mapStatusLogged;
    private bool _activeStatusLogged;
    private DateTime _lastWaitingLog = DateTime.MinValue;
    private DateTime _telemetryActiveSince = DateTime.MinValue;
    private bool _startupBaselineReady;

    public TelemetryHaptics(Action<string> sendHaptic) => _sendHaptic = sendHaptic;

    public void Start()
    {
        _loop ??= Task.Run(LoopAsync);
    }

    private async Task LoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                if (_view == null && !TryOpen())
                {
                    if ((DateTime.UtcNow - _lastWaitingLog).TotalSeconds >= 5)
                    {
                        Console.WriteLine("HAPTICS: waiting for SCSTelemetry...");
                        _lastWaitingLog = DateTime.UtcNow;
                    }
                    await Task.Delay(1000, _cts.Token);
                    continue;
                }

                if (ReadByte(SdkActiveOffset) != 0)
                {
                    if (!_activeStatusLogged)
                    {
                        Console.WriteLine("HAPTICS: telemetry active.");
                        _activeStatusLogged = true;
                        _telemetryActiveSince = DateTime.UtcNow;
                        _startupBaselineReady = false;
                    }
                    ProcessSnapshot();
                }
                else
                {
                    if (_activeStatusLogged)
                    {
                        Console.WriteLine("HAPTICS: telemetry connected, waiting for active game data.");
                        _activeStatusLogged = false;
                    }
                    ResetState(sendRumbleStop: true);
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                CloseMap();
                ResetState(sendRumbleStop: true);
            }

            try { await Task.Delay(PollMs, _cts.Token); }
            catch (OperationCanceledException) { break; }
        }
    }

    private bool TryOpen()
    {
        try
        {
            _map = MemoryMappedFile.OpenExisting(MapName, MemoryMappedFileRights.Read);
            _view = _map.CreateViewAccessor(0, 0, MemoryMappedFileAccess.Read);
            if (!_mapStatusLogged)
            {
                Console.WriteLine("HAPTICS: telemetry connected.");
                _mapStatusLogged = true;
            }
            return true;
        }
        catch
        {
            CloseMap();
            return false;
        }
    }

    private void ProcessSnapshot()
    {
        int gear = ReadInt32(GearOffset);
        int gears = (int)Math.Min(64u, ReadUInt32(GearsOffset));
        float rpm = ReadFloat(EngineRpmOffset);
        float rpmMax = ReadFloat(EngineRpmMaxOffset);
        bool engineEnabled = ReadByte(EngineEnabledOffset) != 0;

        // ETS2/ATS can briefly publish transitional/default telemetry when the
        // cockpit is first entered. Treat those values as the initial baseline.
        // This prevents startup RPM/surface/gear transitions from becoming an
        // unwanted burst of phone vibration.
        if (!_startupBaselineReady)
        {
            if (_telemetryActiveSince == DateTime.MinValue)
                _telemetryActiveSince = DateTime.UtcNow;

            if ((DateTime.UtcNow - _telemetryActiveSince).TotalMilliseconds < StartupGraceMs)
            {
                _engineStateKnown = true;
                _lastEngineEnabled = engineEnabled;
                _lastGear = gear;
                _shiftLatched = false;
                _offRoadLatched = false;
                SendRumbleStop();
                return;
            }

            _startupBaselineReady = true;
            _engineStateKnown = true;
            _lastEngineEnabled = engineEnabled;
            _lastGear = gear;
            _shiftLatched = false;
            _offRoadLatched = false;
            SendRumbleStop();
            return;
        }

        // Engine start/stop. The initial state is silent so connecting to a
        // running truck does not create a false start vibration.
        //
        // The telemetry value can flicker briefly while the game finishes
        // switching into the cockpit. Debounce transitions so that a single
        // real start/stop produces one haptic event instead of repeatedly
        // restarting the vibration until another phone haptic happens to
        // interrupt it.
        if (!_engineStateKnown)
        {
            _engineStateKnown = true;
            _lastEngineEnabled = engineEnabled;
            _pendingEngineState = engineEnabled;
            _pendingEngineStateSince = DateTime.UtcNow;
        }
        else if (engineEnabled != _lastEngineEnabled)
        {
            if (engineEnabled != _pendingEngineState)
            {
                _pendingEngineState = engineEnabled;
                _pendingEngineStateSince = DateTime.UtcNow;
            }
            else if ((DateTime.UtcNow - _pendingEngineStateSince).TotalMilliseconds >= EngineTransitionDebounceMs)
            {
                _lastEngineEnabled = engineEnabled;
                _sendHaptic(engineEnabled ? "HAPTIC|ENGINE_START" : "HAPTIC|ENGINE_STOP");
            }
        }
        else
        {
            _pendingEngineState = engineEnabled;
            _pendingEngineStateSince = DateTime.UtcNow;
        }

        // Gear change: a mechanical double pulse.
        if (gear != _lastGear)
        {
            if (_lastGear != int.MinValue && engineEnabled)
                _sendHaptic(gear > _lastGear ? "HAPTIC|SHIFT_UP" : "HAPTIC|SHIFT_DOWN");

            _lastGear = gear;
            _shiftLatched = false;
        }

        // Shift reminder only when another forward gear exists. Highest gear
        // stays silent, as requested.
        if (gears > 0 && gear > 0 && gear < gears && rpmMax > 100f)
        {
            float ratio = Math.Clamp(rpm / rpmMax, 0f, 1.5f);
            if (!_shiftLatched && ratio >= 0.86f)
            {
                _shiftLatched = true;
                _sendHaptic("HAPTIC|SHIFT_HINT");
            }
            else if (ratio <= 0.78f)
            {
                _shiftLatched = false;
            }
        }
        else
        {
            _shiftLatched = false;
        }

        // Continuous rumble is reserved for the ROAD SURFACE.
        //
        // Previously this also used engine RPM. That meant an idling truck
        // sitting still could continuously vibrate the phone, and the engine
        // rumble would remain present even after leaving gravel and returning
        // to asphalt. Engine start/stop is handled as a discrete event above;
        // road rumble is now completely independent and stops as soon as all
        // powered wheels report a normal road surface.
        int rumbleIntensity = GetPoweredWheelSurfaceIntensity();
        _offRoadLatched = rumbleIntensity > 0;

        if (rumbleIntensity > 0)
            SendRumbleIfChanged(rumbleIntensity, 50);
        else
            SendRumbleStop();
    }

    private void SendRumbleIfChanged(int intensity, int minIntervalMs)
    {
        intensity = Math.Clamp(intensity, 0, 100);
        var now = DateTime.UtcNow;
        if (intensity == _lastRumbleIntensity) return;
        if (_lastRumbleIntensity >= 0 &&
            (now - _lastRumbleSend).TotalMilliseconds < minIntervalMs) return;

        _lastRumbleIntensity = intensity;
        _lastRumbleSend = now;
        if (intensity <= 0) _sendHaptic("HAPTIC|RUMBLE_STOP");
        else _sendHaptic($"HAPTIC|RUMBLE|{intensity}");
    }

    private void SendRumbleStop()
    {
        if (_lastRumbleIntensity == -1) return;
        _lastRumbleIntensity = -1;
        _lastRumbleSend = DateTime.UtcNow;
        _sendHaptic("HAPTIC|RUMBLE_STOP");
    }

    private int GetPoweredWheelSurfaceIntensity()
    {
        int wheelCount = (int)Math.Min(16u, ReadUInt32(80));
        if (wheelCount <= 0) wheelCount = 16;

        int strongest = 0;
        int poweredOnGround = 0;
        int roughPoweredOnGround = 0;

        for (int i = 0; i < wheelCount; i++)
        {
            bool powered = ReadByte(PoweredWheelsOffset + i) != 0;
            if (!powered) continue;

            bool onGround = ReadByte(WheelOnGroundOffset + i) != 0;
            if (!onGround) continue;
            poweredOnGround++;

            uint substance = ReadUInt32(WheelSubstanceOffset + i * 4);
            if (substance >= 25) continue;

            string name = ReadString(SubstanceNamesOffset + checked((int)substance * SubstanceNameSize));
            int intensity = SurfaceIntensity(name);
            if (intensity > 0) roughPoweredOnGround++;
            if (intensity > strongest) strongest = intensity;
        }

        // Require every powered wheel that is currently on the ground to be
        // reporting a known rough surface. This prevents one stale wheel
        // substance value from keeping the phone vibrating after the truck
        // has returned to normal asphalt.
        return poweredOnGround > 0 && roughPoweredOnGround == poweredOnGround
            ? strongest
            : 0;
    }

    private static int SurfaceIntensity(string name)
    {
        if (string.IsNullOrWhiteSpace(name)) return 0;
        string n = name.Trim().ToLowerInvariant();

        if (IsRoadSurface(n)) return 0;
        if (n.Contains("gravel") || n.Contains("cobble") || n.Contains("stone")) return 62;
        if (n.Contains("dirt") || n.Contains("mud") || n.Contains("sand")) return 78;
        if (n.Contains("grass") || n.Contains("ground") || n.Contains("field")) return 70;
        if (n.Contains("snow") || n.Contains("ice")) return 52;

        // Unknown materials are deliberately silent. It is safer to miss a
        // new surface than to classify normal asphalt/road telemetry as a
        // rough surface and leave the rumble running.
        return 0;
    }

    private static bool IsRoadSurface(string name)
    {
        if (string.IsNullOrWhiteSpace(name)) return true;
        string n = name.Trim().ToLowerInvariant();

        return n.Contains("asphalt") || n.Contains("road") || n.Contains("pavement") ||
               n.Contains("concrete") || n.Contains("tarmac") || n.Contains("cement") ||
               n.Contains("bridge") || n.Contains("highway") || n.Contains("street");
    }

    private byte ReadByte(long offset) => _view!.ReadByte(offset);
    private int ReadInt32(long offset) => _view!.ReadInt32(offset);
    private uint ReadUInt32(long offset) => unchecked((uint)_view!.ReadInt32(offset));
    private float ReadFloat(long offset) => _view!.ReadSingle(offset);

    private string ReadString(int offset)
    {
        byte[] bytes = new byte[SubstanceNameSize];
        _view!.ReadArray(offset, bytes, 0, bytes.Length);
        int len = Array.IndexOf(bytes, (byte)0);
        if (len < 0) len = bytes.Length;
        return Encoding.UTF8.GetString(bytes, 0, len);
    }

    private void ResetState(bool sendRumbleStop)
    {
        if (sendRumbleStop)
            SendRumbleStop();

        _shiftLatched = false;
        _offRoadLatched = false;
        _lastGear = int.MinValue;
        _engineStateKnown = false;
        _lastEngineEnabled = false;
        _pendingEngineState = false;
        _pendingEngineStateSince = DateTime.MinValue;
        _telemetryActiveSince = DateTime.MinValue;
        _startupBaselineReady = false;
    }

    private void CloseMap()
    {
        try { _view?.Dispose(); } catch { }
        try { _map?.Dispose(); } catch { }
        _view = null;
        _map = null;
        _mapStatusLogged = false;
        _activeStatusLogged = false;
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _loop?.Wait(500); } catch { }
        try { SendRumbleStop(); } catch { }
        CloseMap();
        _cts.Dispose();
    }
}
