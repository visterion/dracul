package de.visterion.dracul.benchmark;

/** SIGNAL = a prey event (drives the traffic light); CANDIDATE = a pre-screen candidate event
 *  (picked vs rejected comparison). Never mixed (spec §2.6). */
public enum EventKind { SIGNAL, CANDIDATE }
