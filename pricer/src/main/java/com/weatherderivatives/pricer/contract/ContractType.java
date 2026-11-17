package com.weatherderivatives.pricer.contract;

/** The payoff shape of an HDD contract, seen from the long (buying) side. */
public enum ContractType {
  /** Pays {@code tick × max(0, I − K)}: protection against a cold season. */
  CALL,
  /** Pays {@code tick × max(0, K − I)}: protection against a warm season. */
  PUT,
  /** Pays {@code tick × (I − K)}, which can be negative. */
  SWAP
}
