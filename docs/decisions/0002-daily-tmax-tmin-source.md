# 0002 — Daily Tmax/Tmin from ERA5 `mx2t` / `mn2t`

- Status: accepted
- Date: 2026-11-16
- Resolves: spec 001 open question 2

## Context
HDD contracts settle on weather-station daily maximum and minimum temperatures. ERA5
offers two ways to derive a daily Tmax/Tmin:
- max/min of the 24 hourly instantaneous `t2m` values, or
- max/min of `mx2t` / `mn2t` (`maximum/minimum_2m_temperature_since_previous_post_processing`),
  each the extreme over the preceding hour.

Hourly `t2m` misses peaks between the hours, biasing Tmax low and Tmin high. The
daily mean `(Tmax + Tmin) / 2` only partly cancels this.

## Decision
Daily Tmax = max of hourly `mx2t`; daily Tmin = min of hourly `mn2t`, in °C.

**Hour alignment.** An `mx2t`/`mn2t` value stamped `HH:00` covers `HH−1:00..HH:00`. The
UTC day D therefore uses the 24 values stamped **D 01:00 through D+1 00:00**. Downloads
must include 00:00 of the day after the last day of the range.

## Consequences
- Closer to station max/min thermometers, which is what contracts settle on.
- Two variables to download instead of one (`t2m` is no longer needed for HDD).
- The data-prep spec must state the hour alignment and test it (a known-answer test
  with a peak at 00:30 belongs to the previous day).
- Settlement days are UTC days (spec 001). *(Superseded by ADR 0005: per-station windows.)* Contracts with a non-midnight observation
  day (e.g. 09–09) would need a separate decision.
- The `era5-data` skill's default (max/min of hourly `t2m`) is overridden for this
  project by this ADR, which the skill allows ("unless a spec says to use `mx2t`/`mn2t`").
