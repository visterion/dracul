package de.visterion.dracul.voievod;

import de.visterion.dracul.prey.Prey;
import java.util.List;

/** A symbol's contributing open prey, confirmed by >= 2 distinct Strigoi. */
public record ConsensusCluster(String symbol, String companyName, List<Prey> prey) {}
