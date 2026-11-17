package com.weatherderivatives.pricer.model;

/**
 * One season's trend data in a detrended burn (spec 004).
 *
 * @param seasonYear year of the season's first accrual day
 * @param seasonMeanTempC mean of the daily mean temperature over the accrual days, in °C, before
 *     shifting
 * @param shiftDegC amount added to that season's daily Tmax and Tmin, in °C
 */
public record SeasonTrend(int seasonYear, double seasonMeanTempC, double shiftDegC) {}
