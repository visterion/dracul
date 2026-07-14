package de.visterion.dracul.hunting.agora;

/**
 * Provider-neutral fundamental line items requested from Agora's {@code get_fundamental_concepts}
 * tool (the non-US Altman-Z path). Each constant's {@link #name()} is the exact concept key Agora
 * returns under {@code output.concepts.<NAME>}. Only the subset the classic Z-Score needs is
 * modelled here — the US path keeps using raw us-gaap {@code get_company_facts} tags instead.
 */
public enum FundamentalConcept {
    TOTAL_ASSETS,
    CURRENT_ASSETS,
    CURRENT_LIABILITIES,
    TOTAL_LIABILITIES,
    RETAINED_EARNINGS,
    EBIT,
    REVENUE
}
