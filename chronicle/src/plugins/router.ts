import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'chronicle',
      component: () => import('../views/ChronicleView.vue'),
    },
    {
      path: '/verdict/:id',
      name: 'verdict-detail',
      component: () => import('../views/VerdictDetailView.vue'),
    },
    {
      path: '/strigoi/:name',
      name: 'strigoi-detail',
      component: () => import('../views/StrigoiDetailView.vue'),
    },
    {
      path: '/prey/:id',
      name: 'prey-detail',
      component: () => import('../views/PreyDetailView.vue'),
    },
    {
      path: '/watchlist',
      name: 'watchlist',
      component: () => import('../views/WatchlistView.vue'),
    },
    { path: '/portfolio', name: 'portfolio', component: () => import('../views/PortfolioView.vue') },
    { path: '/depots', name: 'depots', component: () => import('../views/DepotsView.vue') },
    // Placeholder for Task C3 (depot position detail view). DepotsView's
    // positions table already navigates here on row click; C3 replaces this
    // stub component with the real detail view — do not remove the route name.
    {
      path: '/depots/:connection/:symbol',
      name: 'depot-position-detail',
      component: () => import('../views/DepotPositionDetailStub.vue'),
    },
    { path: '/exit-signal/:id', name: 'exit-signal-detail', component: () => import('../views/ExitSignalDetailView.vue') },
    { path: '/report', name: 'morning-report', component: () => import('../views/MorningReportView.vue') },
    {
      path: '/patterns',
      name: 'pattern-library',
      component: () => import('../views/PatternLibraryView.vue'),
    },
    {
      path: '/backtest',
      name: 'backtest',
      component: () => import('../views/BacktestView.vue'),
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('../views/SettingsView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

export default router
