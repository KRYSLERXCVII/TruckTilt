using System.IO.MemoryMappedFiles;
using System.Text;

namespace TruckTilt.Host;

/// <summary>
/// Optional ETS2/ATS telemetry reader for TruckTilt haptics.
/// Uses the Rev. 12 SCSTelemetry shared-memory layout.
/// No console spam: haptic events are sent silently to the connected phone.
/// </summary>
internal sealed class TelemetryHaptics : IDisposable
{
    private const string MapName = "Local\\SCSTelemetry";

    // Rev. 12 shared-memory offsets from scs-telemetry-common.hpp.
    private const int SdkActiveOffset = 0;
    private const int GearsOffset = 44;             // config_ui.gears
    private const int GearOffset = 504;             // truck_i.gear
    private const int EngineRpmMaxOffset = 740;     // config_f.engineRpmMax
    private const int EngineRpmOffset = 952;        // truck_f.engineRpm
    private const int WheelSubstanceOffset = 96;    // truck_ui.truck_wheelSubstance[0]
    private const int PoweredWheelsOffset = 1588;   // truck_b.truckWheelPowered[0]
    private const int EngineEnabledOffset = 1576;   // truck_b.engineEnabled
    private const int WheelOnGroundOffset = 1590;   // truck_b.truck_wheelOnGround[0]
    private const int SubstanceNamesOffset = 4400;
    private const int SubstanceNameSize = 64;
    private const int PollMs = 50;

    private readonly Action<string> _sendHaptic;
    private readonly CancellationTokenSource _cts = new();
    private Task? _loop;

    private MemoryMappedFile? _map;
    private MemoryMappedViewAccessor? _view;

    private bool _shiftLatched;
    private int _lastGear = int.MinValue;
    private bool _offRoadLatched;
    private bool _engineStateKnown;
    private bool _lastEngineEnabled;

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
                    await Task.Delay(1000, _cts.Token);
                    continue;
                }

                if (ReadByte(SdkActiveOffset) != 0)
                {
                    ProcessSnapshot();
                }
                else
                {
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

        // Engine start/stop: initialize silently so connecting to a running
        // truck does not create a false "engine start" vibration.
        if (!_engineStateKnown)
        {
            _engineStateKnown = true;
            _lastEngineEnabled = engineEnabled;
        }
        else if (engineEnabled != _lastEngineEnabled)
        {
            _lastEngineEnabled = engineEnabled;
            _sendHaptic(engineEnabled ? "HAPTIC|ENGINE_START" : "HAPTIC|ENGINE_STOP");
        }

        if (gear != _lastGear)
        {
            _lastGear = gear;
            _shiftLatched = false;
        }

        // Small shift reminder only when another forward gear exists.
        // Highest forward gear is intentionally silent.
        if (gears > 0 && gear > 0 && gear < gears && rpmMax > 100f)
        {
            float ratio = rpm / rpmMax;
            if (!_shiftLatched && ratio >= 0.86f)
            {
                _shiftLatched = true;
                _sendHaptic("HAPTIC|SHIFT");
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

        bool offRoad = IsPoweredWheelOffRoad();
        if (offRoad && !_offRoadLatched)
        {
            _offRoadLatched = true;
            _sendHaptic("HAPTIC|RUMBLE_START");
        }
        else if (!offRoad && _offRoadLatched)
        {
            _offRoadLatched = false;
            _sendHaptic("HAPTIC|RUMBLE_STOP");
        }
    }

    private bool IsPoweredWheelOffRoad()
    {
        int wheelCount = (int)Math.Min(16u, ReadUInt32(56)); // config_ui.truckWheelCount
        if (wheelCount <= 0) wheelCount = 16;

        for (int i = 0; i < wheelCount; i++)
        {
            bool powered = ReadByte(PoweredWheelsOffset + i) != 0;
            if (!powered) continue;

            bool onGround = ReadByte(WheelOnGroundOffset + i) != 0;
            if (!onGround) continue;

            uint substance = ReadUInt32(WheelSubstanceOffset + i * 4);
            if (substance >= 25) continue;

            string name = ReadString(SubstanceNamesOffset + checked((int)substance * SubstanceNameSize));
            if (!IsRoadSurface(name)) return true;
        }

        return false;
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
        if (sendRumbleStop && _offRoadLatched)
            _sendHaptic("HAPTIC|RUMBLE_STOP");

        _shiftLatched = false;
        _offRoadLatched = false;
        _lastGear = int.MinValue;
        _engineStateKnown = false;
        _lastEngineEnabled = false;
    }

    private void CloseMap()
    {
        try { _view?.Dispose(); } catch { }
        try { _map?.Dispose(); } catch { }
        _view = null;
        _map = null;
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _loop?.Wait(500); } catch { }
        if (_offRoadLatched)
        {
            try { _sendHaptic("HAPTIC|RUMBLE_STOP"); } catch { }
        }
        CloseMap();
        _cts.Dispose();
    }
}
