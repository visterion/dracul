package de.visterion.dracul.hunting.agora;

import java.time.LocalDate;

/** Metadata of one SEC Form 10-12B spin-off registration, fetched via Agora (no filing-body parse). */
public record SpinoffFiling(
        String ticker,        // may be empty — fresh spin-cos often have no ticker yet
        String companyName,
        String formType,
        LocalDate filingDate,
        String filingUrl
) {}
