# 0005 — Per-station observation windows for daily Tmax/Tmin

- Status: accepted
- Date: 2026-11-19
- Supersedes: the hour-alignment section of [ADR 0002](0002-daily-tmax-tmin-source.md)
  (the choice of `mx2t`/`mn2t` stays)
- Spec: 002 amendment B

## Context
ADR 0002 built every settlement day over the UTC day 00:00..24:00 for both Tmax and Tmin.
That came from the project-wide "dates are UTC `LocalDate`" rule, not from the contracts.
Contracts settle on each station's own observation windows, all in UTC:

| City (station) | Tmax for day D | Tmin for day D |
| --- | --- | --- |
| London-Heathrow (Met Office) | D 08:50 → D+1 08:50 | D−1 08:50 → D 08:50 |
| Amsterdam-Schiphol (KNMI) | D 00:00 → D+1 00:00 | same |
| Paris-Orly (Météo-France) | D 06:00 → D+1 06:00 | D−1 18:00 → D 18:00 |
| Essen (DWD) | D 00:00 → D+1 00:00 | same |

## Decision
- Each station in `stations.toml` has two **required** whole-hour UTC offsets,
  `tmax_window_start_utc_h` and `tmin_window_start_utc_h` (−23..23; the two windows must
  overlap or touch). A window starting at D 00:00 + s is 24 h long and uses the
  `mx2t`/`mn2t` values stamped **D+s+1h .. D+s+24h** (each value covers the hour before
  its stamp). Offsets 0 / 0 reproduce ADR 0002 exactly.
- Tmax(D) is the window starting on D; Tmin(D) is the window ending on D.
- **08:50 is rounded to 09:00**: ERA5 extremes cover whole hours only; 09–09 UTC is the
  Met Office climatological day. London is 9 / −15.
- No default: a silent 00:00 default is how the mismatch arose.
- Hours outside the downloaded years (at most one day before the start and one after the
  end) come from whole-day edge files `era5_<city>_<YYYYMMDD>.nc`, replacing the one-step
  `…0101T00` file.
- With touching windows (London), a day whose Tmin exceeds its Tmax (steady cooling
  through 09:00; ERA5's 08–09 minimum and 09–10 maximum do not share the 09:00 instant)
  gets both set to their midpoint; the count is in metadata `tmin_tmax_adjusted_days`.
  HDD is unchanged. Overlapping windows keep Tmin > Tmax as an error.

## Consequences
- London and Paris daily values change; processed files must be regenerated (no new
  year downloads; up to two edge-day requests per range).
- The Parquet `date` column means "settlement day D" under the station's windows; the
  offsets are recorded in the file metadata and checked by `publish`.
- The 10-minute London difference (08:50 vs 09:00) is a small, accepted basis on top of
  the ERA5-vs-station difference.
