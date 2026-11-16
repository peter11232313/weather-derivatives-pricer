"""Spec 002 / ADR 0003 ACs: bilinear to the station, nearest land fallback, max-after-interp."""

from datetime import date

import numpy as np
import pytest
from synth import LONDON_LATS, LONDON_LONS, field_c, hours, linear_c, make_lsm, make_station

from dataprep.daily import daily_extremes
from dataprep.errors import DataError
from dataprep.interpolate import bilinear, mean_3x3, nearest_cell, nearest_land, to_station

# 4x4 grid around the spec's interpolation example.
LATS4 = [51.0, 51.25, 51.5, 51.75]
LONS4 = [-0.75, -0.5, -0.25, 0.0]


def linear_field(lats, lons, n_times=3):
    """linear_c(lat, lon) + t for t = 0..n_times-1 (degrees C)."""
    lat2, lon2 = np.meshgrid(lats, lons, indexing="ij")
    base = linear_c(lat2, lon2)
    return field_c(np.stack([base + t for t in range(n_times)]), lats, lons)


def identity_field(lats, lons, n_times=2):
    """Each cell's value encodes its coordinates: 100 * lat + lon (unique per cell)."""
    lat2, lon2 = np.meshgrid(lats, lons, indexing="ij")
    base = 100.0 * lat2 + lon2
    return field_c(np.stack([base] * n_times), lats, lons)


# --------------------------------------------------------------------------- bilinear


def test_spec_interpolation_example_gives_13_and_method_bilinear():
    # Cells: (51.25, -0.50)=10, (51.25, -0.25)=12, (51.50, -0.50)=14, (51.50, -0.25)=16.
    # Station at the cell centre (51.375, -0.375): mean of the four = (10+12+14+16)/4 = 13.0.
    field = field_c([[10.0, 12.0], [14.0, 16.0]], [51.25, 51.5], [-0.5, -0.25])
    lsm = make_lsm([51.25, 51.5], [-0.5, -0.25])
    station = make_station(lat_deg=51.375, lon_deg=-0.375, city="example")
    series, method = to_station(field, lsm, station)
    assert method == "bilinear"
    assert series.dims == ("time",)
    assert series.values[0] == pytest.approx(13.0, abs=1e-9)


def test_bilinear_function_matches_spec_example():
    field = field_c([[10.0, 12.0], [14.0, 16.0]], [51.25, 51.5], [-0.5, -0.25])
    assert bilinear(field, 51.375, -0.375).values[0] == pytest.approx(13.0, abs=1e-9)


@pytest.mark.parametrize(
    ("lat", "lon"),
    [
        (51.375, -0.375),  # cell centre
        (51.479, -0.449),  # London Heathrow
        (51.3, -0.7),  # off-centre
        (51.7, -0.01),  # near the north-east edge
        (51.5, -0.3),  # on a latitude grid line
        (51.25, -0.5),  # exactly on a grid node
    ],
)
def test_bilinear_reproduces_linear_field_exactly(lat, lon):
    field = linear_field(LATS4, LONS4)
    out = bilinear(field, lat, lon)
    expected = [linear_c(lat, lon) + t for t in range(3)]
    np.testing.assert_allclose(out.values, expected, rtol=0, atol=1e-9)


def test_to_station_linear_field_all_land_uses_bilinear():
    field = linear_field(LONDON_LATS, LONDON_LONS)
    lsm = make_lsm(LONDON_LATS, LONDON_LONS)
    series, method = to_station(field, lsm, make_station())
    assert method == "bilinear"
    # linear_c(51.479, -0.449) = 10 + 16*0.229 + 8*0.051 = 14.072
    np.testing.assert_allclose(series.values, [14.072, 15.072, 16.072], atol=1e-9)


# --------------------------------------------------------------------------- nearest land


def test_one_surrounding_sea_cell_switches_to_nearest_land():
    # London (51.479, -0.449) sits in the cell lat 51.25..51.5, lon -0.5..-0.25.
    # Make (51.25, -0.5) sea. Nearest land with cos(51.479 deg)=0.6228:
    #   (51.50, -0.50): sqrt((0.051*0.6228)^2 + 0.021^2) = 0.0381  <- winner, value 14.0
    #   (51.50, -0.25): sqrt((0.199*0.6228)^2 + 0.021^2) = 0.1257
    field = linear_field(LONDON_LATS, LONDON_LONS, n_times=2)
    lsm = make_lsm(LONDON_LATS, LONDON_LONS, sea_cells=[(51.25, -0.5)])
    series, method = to_station(field, lsm, make_station())
    assert method == "nearest_land"
    np.testing.assert_allclose(series.values, [14.0, 15.0], atol=1e-9)


def test_lsm_exactly_half_counts_as_land():
    field = linear_field(LONDON_LATS, LONDON_LONS, n_times=1)
    lsm = make_lsm(LONDON_LATS, LONDON_LONS)
    lsm.loc[{"latitude": 51.25, "longitude": -0.5}] = 0.5
    _, method = to_station(field, lsm, make_station())
    assert method == "bilinear"


def test_nearest_land_distance_scales_longitude_by_cos_station_latitude():
    # Station (60.0, 0.1); cos(60 deg) = 0.5. Only two land cells:
    #   A (60.25, 0.00): scaled sqrt(0.25^2 + (0.1*0.5)^2) = 0.2550; plain sqrt(0.25^2+0.1^2)=0.2693
    #   B (60.00, 0.50): scaled 0.4*0.5 = 0.2000;                    plain 0.4000
    # Scaled distance -> B (plain Euclidean degrees would pick A).
    lats = [59.75, 60.0, 60.25, 60.5]
    lons = [-0.25, 0.0, 0.25, 0.5, 0.75]
    field = identity_field(lats, lons)
    lsm = make_lsm(lats, lons, land=0.0, land_cells=[(60.25, 0.0), (60.0, 0.5)])
    out = nearest_land(field, lsm, 60.0, 0.1, city="northtown")
    np.testing.assert_allclose(out.values, 100.0 * 60.0 + 0.5)

    series, method = to_station(field, lsm, make_station(60.0, 0.1, city="northtown"))
    assert method == "nearest_land"
    np.testing.assert_allclose(series.values, 100.0 * 60.0 + 0.5)


@pytest.mark.parametrize(
    ("land", "winner"),
    [
        # All four equidistant diagonal cells land -> lowest lat, then lowest lon.
        ([(51.25, -0.5), (51.25, -0.25), (51.5, -0.5), (51.5, -0.25)], (51.25, -0.5)),
        # Tie between different latitudes -> lower latitude.
        ([(51.25, -0.25), (51.5, -0.5)], (51.25, -0.25)),
        # Tie at the same latitude -> lower longitude.
        ([(51.5, -0.5), (51.5, -0.25)], (51.5, -0.5)),
    ],
)
def test_nearest_land_ties_go_to_lower_latitude_then_lower_longitude(land, winner):
    # Station at the cell centre (51.375, -0.375): the four corners are equidistant.
    field = identity_field(LATS4, LONS4)
    lsm = make_lsm(LATS4, LONS4, land=0.0, land_cells=land)
    out = nearest_land(field, lsm, 51.375, -0.375, city="tieville")
    np.testing.assert_allclose(out.values, 100.0 * winner[0] + winner[1])


def test_no_land_in_box_raises_naming_city():
    field = linear_field(LONDON_LATS, LONDON_LONS, n_times=1)
    lsm = make_lsm(LONDON_LATS, LONDON_LONS, land=0.0)
    with pytest.raises(DataError) as err:
        to_station(field, lsm, make_station(city="atlantis"))
    assert "atlantis" in str(err.value)
    with pytest.raises(DataError) as err:
        nearest_land(field, lsm, 51.479, -0.449, city="atlantis")
    assert "atlantis" in str(err.value)


def test_station_outside_grid_raises_naming_city():
    field = linear_field(LONDON_LATS, LONDON_LONS, n_times=1)
    lsm = make_lsm(LONDON_LATS, LONDON_LONS)
    with pytest.raises(DataError) as err:
        to_station(field, lsm, make_station(lat_deg=48.85, lon_deg=2.35, city="paris"))
    assert "paris" in str(err.value)


# --------------------------------------------------------------------------- max after interp


def test_tmax_is_max_of_interpolated_hourly_not_interpolated_daily_max():
    # Station at the cell centre (51.375, -0.375): bilinear weights are 0.25 each.
    # mx2t: 0 C everywhere, except (51.25,-0.50)=20 at 01:00 and (51.50,-0.25)=20 at 02:00.
    #   interpolated hourly: 01:00 -> 5, 02:00 -> 5, else 0 -> Tmax = 5.0
    #   (interpolating per-cell daily maxima would give (20+0+0+20)/4 = 10.0)
    # mn2t: 0 C everywhere, except (51.25,-0.25)=-20 at 03:00 and (51.50,-0.50)=-20 at 04:00.
    #   interpolated hourly min -> Tmin = -5.0 (interpolated minima would give -10.0)
    times = hours("2024-01-01T01:00", "2024-01-02T00:00")
    mx = np.zeros((24, 2, 2))
    mx[0, 0, 0] = 20.0
    mx[1, 1, 1] = 20.0
    mn = np.zeros((24, 2, 2))
    mn[2, 0, 1] = -20.0
    mn[3, 1, 0] = -20.0
    lats, lons = [51.25, 51.5], [-0.5, -0.25]
    lsm = make_lsm(lats, lons)
    station = make_station(51.375, -0.375, city="peaks")
    mx_s, _ = to_station(field_c(mx, lats, lons, times), lsm, station)
    mn_s, _ = to_station(field_c(mn, lats, lons, times), lsm, station)
    out = daily_extremes(mx_s, mn_s, date(2024, 1, 1), date(2024, 1, 1))
    assert out.tmax_c[0] == pytest.approx(5.0, abs=1e-9)
    assert out.tmin_c[0] == pytest.approx(-5.0, abs=1e-9)


# --------------------------------------------------------------------------- diagnostics


def test_nearest_cell_ignores_land_sea_and_picks_closest():
    # London's nearest node is (51.5, -0.5): linear_c = 14.0.
    field = linear_field(LONDON_LATS, LONDON_LONS, n_times=1)
    assert nearest_cell(field, 51.479, -0.449).values[0] == pytest.approx(14.0, abs=1e-9)


def test_mean_3x3_is_centred_on_the_nearest_cell():
    # Centre (51.5, -0.5) -> 3x3 covers lat 51.25..51.75, lon -0.75..-0.25.
    # Field 0 except 9.0 at (51.75, -0.25) (inside) and 900.0 at (52.0, -0.5) (outside).
    # Mean = 9 / 9 = 1.0.
    values = np.zeros((len(LONDON_LATS), len(LONDON_LONS)))
    values[LONDON_LATS.index(51.75), LONDON_LONS.index(-0.25)] = 9.0
    values[LONDON_LATS.index(52.0), LONDON_LONS.index(-0.5)] = 900.0
    field = field_c(values, LONDON_LATS, LONDON_LONS)
    assert mean_3x3(field, 51.479, -0.449).values[0] == pytest.approx(1.0, abs=1e-12)


def test_mean_3x3_of_linear_field_equals_centre_value():
    field = linear_field(LONDON_LATS, LONDON_LONS, n_times=2)
    np.testing.assert_allclose(mean_3x3(field, 51.479, -0.449).values, [14.0, 15.0], atol=1e-9)
