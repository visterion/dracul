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
  'hunter.daywalker': {
    title: 'Daywalker — Tag-Wächter',
    sections: [
      { heading: 'Rolle', body: 'Bewacht tagsüber deine gehaltenen Positionen und die Watchlist. Erkennt selbst — ohne LLM — auffällige Ereignisse und lässt das LLM nur den Schweregrad einordnen.' },
      { heading: 'Wann', body: 'Werktags während der Handelszeit, in regelmäßigen Abständen (Standard alle 5 Minuten, konfigurierbar — auf diesem System alle 15 Minuten).' },
      { heading: 'Worauf', body: 'Kurssprung, Volumensprung, Insider-Verkauf, negative Nachrichten, Analysten-Abstufung.', table: [{ label: 'Kurssprung', value: '≥ 3 %' }, { label: 'Volumen', value: '≥ 3×' }, { label: 'Schweregrade', value: 'INFO · WARNING · CRITICAL' }] },
      { heading: 'Was er tut', body: 'Schreibt eine Warnung in die Chronik und schickt bei CRITICAL einen Telegram-Hinweis. Er handelt nichts.' },
    ],
  },
  'hunter.daywalker-deep': {
    title: 'Daywalker-Deep — Zweitmeinung',
    sections: [
      { heading: 'Rolle', body: 'Der gründlichere Zweitgutachter des Tag-Wächters. Springt nur ein, wenn sich das LLM bei einer CRITICAL-Warnung selbst unsicher ist.' },
      { heading: 'Wann', body: 'Nur bei geringer Selbstsicherheit (Confidence unter 0,6) auf einer CRITICAL-Warnung — asynchron, im Hintergrund.' },
      { heading: 'Wichtig', body: 'Verzögert oder senkt die ursprüngliche Warnung nie. Ein Schweregrad wird am selben Tag nur hochgestuft, nie gesenkt.' },
    ],
  },
  'hunter.gropar': {
    title: 'Gropar — Abend-Ausstieg',
    sections: [
      { heading: 'Rolle', body: 'Berät abends zu jeder offenen Position: Verkaufen, Reduzieren oder Halten. Ein Berater — er handelt nicht.' },
      { heading: 'Wann', body: 'Werktags um 22:00 UTC.' },
      { heading: 'Woran er misst', body: 'Deterministische Ausstiegs-Indikatoren.', table: [{ label: 'Trailing-Stop', value: 'ATR 22 × 3,0' }, { label: 'Trend', value: 'MA 50 / 200' }, { label: 'Ziel', value: '+40 %' }, { label: 'Stop', value: '−15 %' }] },
      { heading: 'Was er tut', body: 'Schreibt ein Ausstiegssignal (SELL/TRIM/HOLD) in die Chronik, Telegram bei SELL/TRIM.' },
    ],
  },
  'hunter.voievod': {
    title: 'Voievod — Der Rat',
    sections: [
      { heading: 'Rolle', body: 'Bündelt die Funde der Jäger zu einem Urteil (Verdict): nur Symbole, die mindestens zwei verschiedene Strigoi unabhängig markiert haben.' },
      { heading: 'Wann', body: 'Werktags um 08:00 UTC.' },
      { heading: 'Wie', body: 'Der Konsens-Wert wird im Code berechnet; das LLM schreibt nur die Zusammenfassung. Eine bereits von dir getroffene Entscheidung wird nie überschrieben.' },
    ],
  },
  'hunter.voievod-outcome': {
    title: 'Voievod-Outcome — Das Lernen',
    sections: [
      { heading: 'Rolle', body: 'Wöchentliche Rückschau: prüft, wie alte Funde ausgegangen sind, und schlägt daraus neue Muster (Lehren) vor.' },
      { heading: 'Wann', body: 'Samstags um 07:00 UTC.' },
      { heading: 'Was er tut', body: 'Sieht sich Beute an, deren Horizont vor über 30 Tagen ablief, und legt Muster-Vorschläge zur Freigabe an — aktiv erst nach deiner Bestätigung.' },
    ],
  },
  'hunter.renfield': {
    title: 'Renfield — Vorschläge',
    sections: [
      { heading: 'Rolle', body: 'Tägliche Durchsicht deiner Watchlist. Trägt Fakten je Symbol zusammen und lässt das LLM daraus gerankte Handelsvorschläge machen.' },
      { heading: 'Wann', body: 'Werktags um 12:00 UTC.' },
      { heading: 'Wichtig', body: 'Das LLM hat dabei keine Werkzeuge und handelt nie — es schlägt nur vor (bis zu 30 Symbole). Telegram-Digest je Lauf.' },
    ],
  },
  'hunter.executor': {
    title: 'Executor — Die Ausführung',
    sections: [
      { heading: 'Rolle', body: 'Der einzige Agent, der wirklich Orders platziert — bewachte Einstiege (mit Ziel und Stop) und Ausstiege.' },
      { heading: 'Bewacht durch', body: 'Veto-Katalog, Order-Guard, Positions-Sizer, Stop-Ratchet — das LLM kann keine Order direkt auslösen, nur bewachte Werkzeuge.', table: [{ label: 'Mindest-Confidence', value: '0,65' }, { label: 'Max. Positionen', value: '5' }, { label: 'Max. je Sektor', value: '2' }, { label: 'Tempo', value: '2 / Woche' }, { label: 'Signal-Alter', value: '≤ 5 Tage' }] },
      { heading: 'Wichtig', body: 'Budget und Handelsplatz sind konfigurierbar (Standard: 10.000, Paper/Saxo-Sim). Läuft nur, wenn aktiviert.' },
    ],
  },
  'decision.overview': {
    title: 'Wie Dracul entscheidet',
    sections: [
      { heading: 'Was Dracul ist', body: 'Dracul ist ein autonomer Recherche-Assistent für Aktien-Anomalien — kein Auto-Trader, keine Anlageberatung. Er findet Kandidaten; entscheiden tust du.' },
      { heading: 'Die Jäger (Strigoi)', body: 'Sechs spezialisierte Muster-Jäger wachen nachts und durchsuchen den Markt nach je einem dokumentierten Muster.', table: [{ label: 'Spin', value: 'Abspaltungen (Spin-offs)' }, { label: 'Insider', value: 'Insider-Käufe im Cluster' }, { label: 'Echo', value: 'Drift nach Quartalszahlen (PEAD)' }, { label: 'Lazarus', value: 'Qualität am 52-Wochen-Tief' }, { label: 'Index', value: 'Index-Aufnahme-Drift' }, { label: 'Merger', value: 'Übernahme-Arbitrage' }] },
      { heading: 'Vom Signal zur Beute', body: 'Vor dem teuren LLM filtern deterministische Vor-Prüfungen im Code. Nur was durchkommt, geht an das LLM. Ergebnis ist „Beute" (ein Fund) mit einer Confidence.' },
      { heading: 'Der Rat (Voievod)', body: 'Werktags um 08:00 UTC bündelt der Rat Symbole, die mindestens zwei Jäger unabhängig markiert haben, zu einem Urteil. Der Konsens-Wert kommt aus dem Code, das LLM schreibt nur die Zusammenfassung — deine Entscheidung wird nie überschrieben.' },
      { heading: 'Die Wächter', body: 'Tagsüber prüft der Daywalker gehaltene Positionen regelmäßig (Standard alle 5 Min., konfigurierbar — hier alle 15 Min.) auf Kurssprung ≥3 %, Volumen ≥3×, Insider-Verkauf, negative News, Abstufung. Abends (22:00 UTC) rät der Gropar zu Verkaufen/Reduzieren/Halten (ATR-Stop 22×3,0, MA 50/200, Ziel +40 % / Stop −15 %).' },
      { heading: 'Die Vorschläge (Renfield)', body: 'Werktags um 12:00 UTC sichtet Renfield bis zu 30 Watchlist-Symbole und macht gerankte Vorschläge — ohne Werkzeuge, ohne selbst zu handeln.' },
      { heading: 'Die Ausführung (Executor)', body: 'Der einzige Agent, der Orders platziert — streng bewacht. Budget und Handelsplatz sind konfigurierbar (Standard 10.000, Paper/Saxo-Sim); er läuft nur, wenn aktiviert.', table: [{ label: 'Mindest-Confidence', value: '0,65' }, { label: 'Max. Positionen', value: '5' }, { label: 'Max. je Sektor', value: '2' }, { label: 'Tempo', value: '2 / Woche' }, { label: 'Signal-Alter', value: '≤ 5 Tage' }] },
      { heading: 'Das Lernen (Voievod-Outcome)', body: 'Samstags um 07:00 UTC schaut Dracul zurück: Beute, deren Horizont vor über 30 Tagen ablief, wird ausgewertet und zu neuen Muster-Vorschlägen — aktiv erst nach deiner Freigabe.' },
      { heading: 'Deine Entscheidung', body: 'Jeden Morgen prüfst du die Funde in der Chronik und entscheidest. Dracul führt nur aus, was du (oder der aktivierte, bewachte Executor) freigibst.' },
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
