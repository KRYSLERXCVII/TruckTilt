using System.IO.MemoryMappedFiles;
using System.Text;

namespace TruckTilt.Host;

/// <summary>
/// Lightweight reader for the SCSTelemetry shared-memory layout used by the
/// community SCS telemetry plug-in. It is deliberately optional: if the map
/// is not present, TruckTilt continues to work normally without telemetry.
/// </summary>
internal sealed class TelemetryHaptics : IDisposable
{
    private const string MapName = "Local\\SCSTelemetry";
    private const int SdkActiveOffset = 0;
    private const int GearOffset = 500;
    private const int GearsOffset = 68;
    private const int WheelSubstanceOffset = 120;
    private const int EngineRpmOffset = 704;
    private const int EngineRpmMaxOffset = 736;
    private const int PoweredWheelsOffset = 1598;
    private const int WheelOnGroundOffset = 1590;
    private const int SubstanceNamesOffset = 4400;
    private const int SubstanceNameSize = 64;
    private const int PollMs = 50;

    private readonly Action<string> _sendHaptic;
    private readonly CancellationTokenSource _cts = new();
    private Task? _loop;
    private bool _shiftLatched;
    private int _lastGear = int.MinValue;
    private bool _offRoadLatched;
    private DateTime _lastOffRoadPulse = DateTime.MinValue;
    private MemoryMappedFile? _map;
    private MemoryMappedViewAccessor? _view;

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

                if (!ReadByte(SdkActiveOffset).Equals(0))
                    ProcessSnapshot();
                else
                    ResetState();
            }
            catch (OperationCanceledException) { break; }
            catch
            {
                CloseMap();
                ResetState();
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
        int gears = (int)Math.Max(0u, ReadUInt32(GearsOffset));
        float rpm = ReadFloat(EngineRpmOffset);
        float rpmMax = ReadFloat(EngineRpmMaxOffset);

        if (gear != _lastGear)
        {
            _lastGear = gear;
            _shiftLatched = false;
        }

        // Small shift reminder only when another forward gear exists. Once
        // the truck is in the highest forward gear, this deliberately stays quiet.
        if (gears > 0 && gear > 0 && gear < gears && rpmMax > 100f)
        {
            float ratio = rpm / rpmMax;
            if (!_shiftLatched && ratio >= 0.86f)
            {
                _shiftLatched = true;
                _sendHaptic("HAPTIC|1");
            }
            else if (ratio <= 0.78f)
            {
                _shiftLatched = false;
            }
        }
        else if (gear >= gears || gear <= 0)
        {
            _shiftLatched = false;
        }

        bool offRoad = IsPoweredWheelOffRoad();
        if (offRoad && !_offRoadLatched)
        {
            _offRoadLatched = true;
            _lastOffRoadPulse = DateTime.UtcNow;
            _sendHaptic("HAPTIC|2");
        }
        else if (offRoad && (DateTime.UtcNow - _lastOffRoadPulse).TotalMilliseconds >= 700)
        {
            _lastOffRoadPulse = DateTime.UtcNow;
            _sendHaptic("HAPTIC|2");
        }
        else if (!offRoad)
        {
            _offRoadLatched = false;
        }
    }

    private bool IsPoweredWheelOffRoad()
    {
        for (int i = 0; i < 16; i++)
        {
            bool powered = ReadByte(PoweredWheelsOffset + i) != 0;
            if (!powered) continue;

            bool onGround = ReadByte(WheelOnGroundOffset + i) != 0;
            if (!onGround) continue;

            uint substance = ReadUInt32(WheelSubstanceOffset + i * 4);
            string name = ReadString(SubstanceNamesOffset + checked((int)substance * SubstanceNameSize));
            if (!IsRoadSurface(name)) return true;
        }
        return false;
    }

    private static bool IsRoadSurface(string name)
    {
        if (string.IsNullOrWhiteSpace(name)) return false;
        string n = name.Trim().ToLowerInvariant();
        // SCS substance identifiers vary by game/version. Treat clearly road-like
        // surfaces as road and let gravel/dirt/grass/sand/etc. produce feedback.
        return n.Contains("asphalt") || n.Contains("road") || n.Contains("pavement") ||
               n.Contains("concrete") || n.Contains("tarmac") || n.Contains("cement") ||
               n.Contains("bridge");
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

    private void ResetState()
    {
        _shiftLatched = false;
        _offRoadLatched = false;
        _lastGear = int.MinValue;
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
        CloseMap();
        _cts.Dispose();
    }
}
