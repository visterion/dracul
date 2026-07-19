import type { ExplainerTable } from './explainers'

const de: ExplainerTable = {
  'orders.bracket': {
    title: 'Geschützter Auftrag',
    sections: [
      {
        anchor: 'bracket',
        heading: 'Was ist ein geschützter Auftrag?',
        body: 'Ein Kauf mit automatischer Absicherung — drei zusammengehörige Orders: Einstieg (der Kauf), Ziel (Gewinnmitnahme) und Stop (Verlustschutz). Ziel und Stop werden erst scharf, wenn der Einstieg ausgeführt ist.',
      },
    ],
  },
  'hunter.spin': {
    title: 'strigoi-spin — Abspaltungen',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Spaltet ein Konzern eine Tochter als eigene Aktie ab, werfen viele Fonds die neue Aktie automatisch ab (sie passt nicht in ihr Mandat). Dieser Zwangsverkauf drückt den Kurs unter den fairen Wert.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'SEC-Registrierungen (Form 10-12B) der letzten 60 Tage plus das Term-Sheet der Abspaltung (Verhältnis, Stichtag, Größe).' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Verfolgt jede Abspaltung durch ihre Phasen und meldet einen Kandidaten im Zwangsverkaufs-Fenster nach der Abspaltung.' },
    ],
  },
  'hunter.insider': {
    title: 'strigoi-insider — Insider-Käufe im Cluster',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Kaufen mehrere Führungskräfte kurz hintereinander eigene Aktien, wissen sie oft etwas Gutes, das der Markt noch nicht sieht.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'SEC Form-4-Meldungen, gefiltert auf Cluster (≥3 Käufer in 30 Tagen, zusammen über 500.000 $); angereichert mit Firmengröße, Analystenabdeckung und ob die Käufe routinemäßig oder opportunistisch sind.' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Bevorzugt kleine, unbeachtete Firmen mit echten (nicht geplanten) Käufen und meldet die überzeugendsten Cluster.' },
    ],
  },
  'hunter.echo': {
    title: 'strigoi-echo — Drift nach Quartalszahlen',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Nach überraschend guten Zahlen läuft der Kurs oft noch Wochen weiter — der Markt reagiert zu langsam.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'Zahlen-Kalender, Gewinn-Überraschung (Zulassung ab 5 % EPS-Überraschung; der SUE-Wert verfeinert danach die Überzeugung), Marktreaktion am Berichtstag und Analysten-Revisionen.' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Nur echte, cash-gedeckte Überraschungen (keine Bilanztricks), kein baldiger nächster Bericht, und der Markt hat am Berichtstag positiv reagiert.' },
    ],
  },
  'hunter.lazarus': {
    title: 'strigoi-lazarus — Qualität am Jahrestief',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Solide Firmen, die nur wegen Marktpanik nahe ihrem Jahrestief notieren, erholen sich oft — Qualität zum Ausverkaufspreis.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'Deine Watchlist nahe dem 52-Wochen-Tief (rund 10 %); Piotroski-F-Score (Bilanzqualität), Altman-Z (Pleiterisiko), Bewertung und Timing-Signale.' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Verlangt hohe Bilanzqualität, schließt Pleitekandidaten und „fallende Messer" aus und meldet nur günstig-und-gesund.' },
    ],
  },
  'hunter.index': {
    title: 'strigoi-index — Index-Aufnahme',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Wird eine Aktie neu in einen großen Index aufgenommen, müssen Index-Fonds sie kaufen — der erzwungene Kauf treibt den Kurs bis zum Stichtag.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'Angekündigte Index-Änderungen (S&P-Pressemitteilungen, Russell) mit Ankündigungs- und Stichtag sowie Liquidität.' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Meldet nur, solange das Kauf-Fenster (heute bis Stichtag) offen ist; bereits wirksame Aufnahmen sind zu spät.' },
    ],
  },
  'hunter.merger': {
    title: 'strigoi-merger — Übernahme-Arbitrage',
    sections: [
      { anchor: 'idea', heading: 'Idee', body: 'Nach Ankündigung einer Übernahme handelt die Zielaktie knapp unter dem Angebotspreis — die Lücke ist der Gewinn, wenn der Deal durchgeht.' },
      { anchor: 'inputs', heading: 'Inputs & Jagdgründe', body: 'SEC-Deal-Filings (DEFM14A, Tender Offers) der letzten 45 Tage, Angebotspreis und Bedingungen aus dem Term-Sheet, Kurs und erwarteter Abschluss.' },
      { anchor: 'strike', heading: 'Wie er zuschlägt', body: 'Wägt den annualisierten Spread gegen das Absturzrisiko bei Deal-Bruch, dämpft Aktien-Deals und koppelt den Horizont an den erwarteten Abschluss.' },
    ],
  },
  'hunter.overview': {
    title: 'Die Jäger (Meute)',
    sections: [
      { anchor: 'was', heading: 'Was sind die Jäger?', body: 'Sechs spezialisierte Jäger („Strigoi") suchen nachts je genau eine dokumentierte Marktanomalie und legen Funde in die Datenbank. Jeder Jäger hat eine eigene Idee, eigene Datenquellen und einen eigenen Auslöser. Über das (i) an jedem Jäger siehst du seine Idee, Inputs und wie er zuschlägt.' },
      { anchor: 'spin', heading: 'spin — Abspaltungen', body: 'Zwangsverkauf nach der Abspaltung einer Tochter drückt den Kurs unter den fairen Wert.' },
      { anchor: 'insider', heading: 'insider — Insider-Käufe', body: 'Mehrere Führungskräfte kaufen kurz hintereinander eigene Aktien — ein Cluster ist Überzeugung.' },
      { anchor: 'echo', heading: 'echo — Drift nach Zahlen', body: 'Nach überraschend guten Quartalszahlen läuft der Kurs oft noch Wochen weiter.' },
      { anchor: 'lazarus', heading: 'lazarus — Qualität am Tief', body: 'Gesunde Firmen nahe ihrem Jahrestief erholen sich oft — Qualität zum Ausverkaufspreis.' },
      { anchor: 'index', heading: 'index — Index-Aufnahme', body: 'Neu-Aufnahme in einen großen Index zwingt Index-Fonds zum Kauf bis zum Stichtag.' },
      { anchor: 'merger', heading: 'merger — Übernahme-Arbitrage', body: 'Die Zielaktie handelt unter dem Angebotspreis — die Lücke ist der Gewinn, wenn der Deal durchgeht.' },
    ],
  },
  'orders.roles': {
    title: 'Einstieg, Ziel & Stop',
    sections: [
      { anchor: 'entry', heading: 'Einstieg', body: 'Der eigentliche Kauf. Solange er nicht ausgeführt ist, sind Ziel und Stop noch nicht scharf.' },
      { anchor: 'target', heading: 'Ziel (Gewinnmitnahme)', body: 'Eine Limit-Order, die die Position mit Gewinn verkauft, wenn der Kurs das Ziel erreicht.' },
      { anchor: 'stop', heading: 'Stop (Verlustschutz)', body: 'Eine Stop-Order, die die Position schließt, wenn der Kurs unter das Stop-Level fällt.' },
      { anchor: 'limitVsStop', heading: 'Limit vs. Stop', body: 'Limit = kauft/verkauft nur zu diesem Preis oder besser. Stop = wird erst zur Order, wenn der Kurs das Stop-Level erreicht.' },
    ],
  },
  'depot.metrics': {
    title: 'Cash, Investiert & Kaufkraft',
    sections: [
      { anchor: 'cash', heading: 'Cash', body: 'Freies Geld auf dem Konto, nicht in Positionen gebunden.' },
      { anchor: 'invested', heading: 'Investiert', body: 'Der aktuelle Wert der offenen Positionen.' },
      { anchor: 'buyingPower', heading: 'Kaufkraft', body: 'Der Betrag, für den aktuell noch gekauft werden kann.' },
    ],
  },
  'calibration': {
    title: 'Executor-Kalibrierung',
    sections: [
      { anchor: 'brier', heading: 'Brier-Score', body: 'Ein Maß, wie gut die Wahrscheinlichkeits-Schätzungen des Executors treffen (kleiner ist besser, 0 wäre perfekt). Braucht mindestens 30 Fälle, sonst steht „unzureichende Daten".' },
      { anchor: 'vetoPrecision', heading: 'Veto-Präzision', body: 'Wie oft ein abgelehnter Handel im Nachhinein zu Recht abgelehnt war. Die folgenden Gründe sind die häufigsten — das ist nicht die vollständige Liste (es gibt weitere, seltenere Gründe wie Budget- oder Konzentrationsgrenzen).' },
      { anchor: 'brokerError', heading: 'BROKER_ERROR', body: 'Der Broker hat abgelehnt oder es gab einen technischen Fehler.' },
      { anchor: 'noStop', heading: 'NO_STOP', body: 'Es ließ sich kein gültiger Stop berechnen — ohne Absicherung wird nicht gehandelt.' },
      { anchor: 'lowConfidence', heading: 'LOW_CONFIDENCE', body: 'Die Überzeugung lag unter der Mindestschwelle.' },
      { anchor: 'paceLimit', heading: 'PACE_LIMIT', body: 'Das wöchentliche Limit an neuen Einstiegen war erreicht.' },
      { anchor: 'cooldown', heading: 'COOLDOWN', body: 'Abklingzeit nach dem letzten Handel im selben Namen.' },
      { anchor: 'belowAnchor', heading: 'BELOW_ANCHOR', body: 'Der Kurs stand auf der ungültig machenden Seite des Anker-/Referenzniveaus.' },
      { anchor: 'avgR', heading: 'Ø R 20T / 60T', body: 'Das durchschnittliche hypothetische Ergebnis abgelehnter Signale in „R" (Vielfaches des riskierten Betrags) nach 20 bzw. 60 Handelstagen — optimistisch gerechnet (Ausführung zum Referenzpreis unterstellt).' },
      { anchor: 'stoppedOut', heading: 'Ausgestoppt', body: 'Der Anteil der Fälle, die per Stop mit Verlust geschlossen worden wären.' },
      { anchor: 'slippage', heading: 'Slippage', body: 'Die Differenz zwischen erwartetem und tatsächlichem Ausführungspreis.' },
      { anchor: 'hardExitLatency', heading: 'Hard-Exit-Latenz', body: 'Die Zeit von der Erkennung eines Notausstiegs bis zur Aufgabe der Order beim Broker.' },
    ],
  },
}

export default de
