package com.recrutment.application.enums;

/**
 * Contract types offered in Tunisia / VERMEG context. Distinct from the job's
 * EmploymentType because the offer is a legal agreement with finer-grained
 * terms (CDI vs CDD vs intern/alternance).
 */
public enum ContractType {
    CDI,           // Contrat à durée indéterminée — permanent
    CDD,           // Contrat à durée déterminée — fixed term
    INTERNSHIP,    // Stage
    ALTERNANCE,    // Alternance / apprenticeship
    FREELANCE      // Independent / consulting
}
