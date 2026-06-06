export default {
  common: {
    save: 'Save',
    loading: 'Loading …',
    language: 'Language',
  },
  settings: {
    language: {
      navLabel: 'Language',
      title: 'Language',
      subtitle: 'Language of the interface and the AI narratives',
      german: 'German',
      english: 'English',
    },
  },
  chronicle: {
    error: {
      retry: 'Retry',
    },
    banner: {
      newPrey: 'new prey',
      verdictSingular: 'verdict',
      verdictPlural: 'verdicts',
      alertSingular: 'daywalker alert',
      alertPlural: 'daywalker alerts',
      lessonSingular: 'lesson pending',
      lessonPlural: 'lessons pending',
    },
    sections: {
      verdicts: 'verdicts (consensus from multiple strigoi)',
      individualPrey: 'individual prey',
      daywalkerAlerts: 'daywalker alerts (today)',
      pendingLessons: 'pending lessons from voievod',
    },
    ariaLabels: {
      daywalkerAlerts: 'Daywalker alerts',
      pendingLessons: 'Pending lessons',
    },
    emptyState: {
      noVerdicts: 'No consensus findings yet.',
      noPreyYet: 'The Strigoi have not yet returned tonight.',
    },
  },
  verdict: {
    notFound: {
      message: 'Verdict not found.',
      backLink: '← Back to chronicle',
    },
    breadcrumb: {
      chronicle: 'chronicle',
      verdict: 'verdict',
    },
    meta: {
      discovered: 'discovered',
      strigoi: 'strigoi',
    },
    sections: {
      thesis: 'consensus thesis',
      signals: 'signals',
      risks: 'risks',
      contributingStrigoi: 'contributing strigoi',
    },
    sidebar: {
      decisionTitle: 'Decision',
      notesTitle: 'Notes',
      statsTitle: 'Quick stats',
      daywalkerTitle: 'Daywalker status',
      daywalkerHint: 'Add to watchlist to enable Daywalker',
      notesEmpty: 'No notes yet.',
      notesPlaceholder: 'Add your reasoning...',
      addNote: 'Add note',
    },
    stats: {
      currentPrice: 'Current price',
      consensus: 'Consensus',
      avgConfidence: 'Avg confidence',
      timeHorizon: 'Time horizon',
      discovered: 'Discovered',
    },
    decisions: {
      track: 'Track on Watchlist',
      interesting: 'Mark as Interesting',
      acted: 'Acted',
      dismiss: 'Dismiss',
    },
  },
  strigoi: {
    notFound: {
      message: 'Strigoi not found.',
      backLink: '← Back to chronicle',
    },
    breadcrumb: {
      chronicle: 'chronicle',
      strigoi: 'strigoi',
    },
    schedule: {
      lastRun: 'last run:',
      next: 'next:',
    },
    stats: {
      huntsThisMonth: 'Hunts This Month',
      huntsScheduled: 'of {n} scheduled',
      avgPreyPerHunt: 'Avg Prey per Hunt',
      hitRate90d: 'Hit Rate (90d)',
      hitRateDetail: '{num} of {den} prey within thesis',
    },
    sections: {
      recentRuns: 'recent runs',
      recentPrey: 'recent prey',
      configuration: 'configuration',
      performance: 'performance over time',
    },
    config: {
      scheduleTitle: 'Schedule',
      llmTitle: 'LLM & Budget',
      cron: 'Cron',
      nextRun: 'Next run',
      disabled: 'Disabled',
      disabledYes: 'Yes',
      disabledNo: 'No',
      tier: 'Tier',
      dailyBudget: 'Daily budget',
      monthlyBudget: 'Monthly budget',
      primary: 'Primary',
      fallback: 'Fallback',
      used: '(used: ${n})',
    },
    chart: {
      hitRate: 'hit rate',
      preyCount: 'prey count',
    },
  },
}
