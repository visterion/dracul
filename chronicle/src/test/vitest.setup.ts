import { vi } from 'vitest'

// vue-echarts renders to a real <canvas> via zrender's CanvasPainter, which
// happy-dom/jsdom cannot back with a real 2D context. Without this mock every
// spec that mounts PriceChart (which wraps <v-chart>) throws async
// "Cannot read properties of null (reading 'clearRect')" errors from zrender's
// animation loop after the test has already finished asserting.
// Replace the real <v-chart> with an inert stub so component tests never
// need a real canvas.
vi.mock('vue-echarts', () => ({
  default: {
    name: 'VChart',
    props: ['option', 'autoresize'],
    template: '<div class="v-chart-stub" />',
  },
}))
