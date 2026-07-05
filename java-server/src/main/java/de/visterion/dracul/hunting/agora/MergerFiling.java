package de.visterion.dracul.hunting.agora;

import java.time.LocalDate;

/** SEC deal-filing metadata (DEFM14A merger proxy or SC TO-T tender offer), fetched via Agora. ticker may be empty. */
public record MergerFiling(
        String ticker,
        String companyName,
        String formType,
        LocalDate filingDate,
        String filingUrl
) {}
