export default {
  common: {
    save: 'Speichern',
    loading: 'Laden …',
    language: 'Sprache',
  },
  settings: {
    language: {
      navLabel: 'Sprache',
      title: 'Sprache',
      subtitle: 'Sprache der Oberfläche und der KI-Texte',
      german: 'Deutsch',
      english: 'Englisch',
    },
  },
  chronicle: {
    error: {
      retry: 'Wiederholen',
    },
    banner: {
      newPrey: 'neue Beute',
      verdictSingular: 'Urteil',
      verdictPlural: 'Urteile',
      alertSingular: 'Tagesläufer-Alarm',
      alertPlural: 'Tagesläufer-Alarme',
      lessonSingular: 'Lektion ausstehend',
      lessonPlural: 'Lektionen ausstehend',
    },
    sections: {
      verdicts: 'Urteile (Konsens mehrerer Strigoi)',
      individualPrey: 'Einzelne Beute',
      daywalkerAlerts: 'Tagesläufer-Alarme (heute)',
      pendingLessons: 'Ausstehende Lektionen vom Woiwoden',
    },
    ariaLabels: {
      daywalkerAlerts: 'Tagesläufer-Alarme',
      pendingLessons: 'Ausstehende Lektionen',
    },
    emptyState: {
      noVerdicts: 'Noch keine Konsens-Funde.',
      noPreyYet: 'Die Strigoi sind heute Nacht noch nicht zurückgekehrt.',
    },
  },
  verdict: {
    notFound: {
      message: 'Urteil nicht gefunden.',
      backLink: '← Zurück zur Chronik',
    },
    breadcrumb: {
      chronicle: 'chronik',
      verdict: 'urteil',
    },
    meta: {
      discovered: 'entdeckt',
      strigoi: 'Strigoi',
    },
    sections: {
      thesis: 'Konsens-These',
      signals: 'Signale',
      risks: 'Risiken',
      contributingStrigoi: 'Beteiligte Strigoi',
    },
    sidebar: {
      decisionTitle: 'Entscheidung',
      notesTitle: 'Notizen',
      statsTitle: 'Schnellstatistik',
      daywalkerTitle: 'Tagesläufer-Status',
      daywalkerHint: 'Zur Watchlist hinzufügen, um Tagesläufer zu aktivieren',
      notesEmpty: 'Noch keine Notizen.',
      notesPlaceholder: 'Begründung eintragen …',
      addNote: 'Notiz hinzufügen',
    },
    stats: {
      currentPrice: 'Aktueller Kurs',
      consensus: 'Konsens',
      avgConfidence: 'Ø Konfidenz',
      timeHorizon: 'Zeithorizont',
      discovered: 'Entdeckt',
    },
    decisions: {
      track: 'Auf Watchlist setzen',
      interesting: 'Als interessant markieren',
      acted: 'Gehandelt',
      dismiss: 'Verwerfen',
    },
  },
  strigoi: {
    notFound: {
      message: 'Strigoi nicht gefunden.',
      backLink: '← Zurück zur Chronik',
    },
    breadcrumb: {
      chronicle: 'chronik',
      strigoi: 'strigoi',
    },
    schedule: {
      lastRun: 'letzter Lauf:',
      next: 'nächster:',
    },
    stats: {
      huntsThisMonth: 'Jagden diesen Monat',
      huntsScheduled: 'von {n} geplant',
      avgPreyPerHunt: 'Ø Beute pro Jagd',
      avgPreyDetail: 'Median 1, Max 7',
      hitRate90d: 'Trefferquote (90T)',
      hitRateDetail: '{num} von {den} Beute innerhalb der These',
    },
    sections: {
      recentRuns: 'Letzte Läufe',
      recentPrey: 'Letzte Beute',
      configuration: 'Konfiguration',
      performance: 'Leistung über Zeit',
    },
    run: {
      traceLabel: 'Ablaufprotokoll für Lauf {id}',
      today: 'heute',
      yesterday: 'gestern',
      daysAgo: 'T zurück',
      preyUnit: 'Beute',
    },
    config: {
      scheduleTitle: 'Zeitplan',
      llmTitle: 'LLM & Budget',
      cron: 'Cron',
      nextRun: 'Nächster Lauf',
      disabled: 'Deaktiviert',
      disabledYes: 'Ja',
      disabledNo: 'Nein',
      tier: 'Tier',
      dailyBudget: 'Tagesbudget',
      monthlyBudget: 'Monatsbudget',
      primary: 'Primär',
      fallback: 'Fallback',
      used: '(verwendet: {n})',
    },
    chart: {
      hitRate: 'Trefferquote',
      preyCount: 'Beuteanzahl',
    },
  },
}
