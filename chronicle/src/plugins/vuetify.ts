import 'vuetify/styles'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import '@mdi/font/css/materialdesignicons.css'

export default createVuetify({
  components,
  directives,
  theme: {
    defaultTheme: 'dracul',
    themes: {
      dracul: {
        dark: true,
        colors: {
          background: '#0A0A0F',
          surface: '#13131A',
          'surface-bright': '#1F1F28',
          'surface-light': '#2A2A35',
          'surface-variant': '#13131A',
          'on-background': '#F5F1E8',
          'on-surface': '#F5F1E8',
          primary: '#A11D2C',
          'primary-darken-1': '#7A1622',
          secondary: '#B8945C',
          'secondary-darken-1': '#9A7847',
          accent: '#B8945C',
          error: '#A11D2C',
          info: '#6B6B70',
          success: '#4A8B5C',
          warning: '#B8945C',
        },
        variables: {
          'border-color': '255, 241, 232',
          'border-opacity': 0.08,
          'high-emphasis-opacity': 1,
          'medium-emphasis-opacity': 0.78,
          'disabled-opacity': 0.4,
          'idle-opacity': 0.04,
          'hover-opacity': 0.08,
          'focus-opacity': 0.12,
          'selected-opacity': 0.12,
          'activated-opacity': 0.16,
          'pressed-opacity': 0.16,
          'dragged-opacity': 0.08,
        },
      },
    },
  },
  defaults: {
    VBtn: {
      variant: 'flat' as const,
      rounded: 'sm' as const,
      style: 'text-transform: none; letter-spacing: 0;',
    },
    VCard: {
      variant: 'flat' as const,
      rounded: 'sm' as const,
    },
    VTextField: {
      variant: 'outlined' as const,
      density: 'comfortable' as const,
    },
  },
})
