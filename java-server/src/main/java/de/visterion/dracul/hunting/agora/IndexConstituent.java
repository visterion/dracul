package de.visterion.dracul.hunting.agora;

/** One member of a stock index as delivered by Agora's {@code get_index_constituents}. */
public record IndexConstituent(String symbol, String companyName, String sector) {}
