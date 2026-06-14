export type FieldKind = 'text' | 'textarea' | 'number' | 'select' | 'toggle'

export interface FieldDescriptor {
  key: string
  label: string
  kind: FieldKind
  help?: string
  required?: boolean
  min?: number
  max?: number
  options?: { value: string; label: string }[]
  advanced?: boolean
}
