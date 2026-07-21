import de from './explainers.de'
import en from './explainers.en'

/** One explained concept: a title plus ordered sections. `anchor` lets an
 *  inline InfoDot jump straight to the matching section inside the panel. */
export interface ExplainerSection {
  heading: string
  body: string
  anchor?: string
  bullets?: string[]
  table?: { label: string; value: string }[]
}
export interface Explainer {
  title: string
  sections: ExplainerSection[]
}
export type ExplainerTable = Record<string, Explainer>

function tableFor(locale: string): ExplainerTable {
  return locale === 'en' ? en : de
}

/** True when the key resolves in the given locale (or the de fallback).
 *  InfoDot uses this to render nothing for an unknown/new topic instead of
 *  crashing — satisfies the spec's "missing key must not crash" rule. */
export function hasExplainer(locale: string, key: string): boolean {
  return key in tableFor(locale) || key in de
}

export function getExplainer(locale: string, key: string): Explainer {
  const hit = tableFor(locale)[key] ?? de[key]
  if (!hit) throw new Error(`Unknown explainer key: ${key}`)
  return hit
}

export function explainerKeys(locale: string): string[] {
  return Object.keys(tableFor(locale))
}
