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
    { path: '/exit-signal/:id', name: 'exit-signal-detail', component: () => import('../views/ExitSignalDetailView.vue') },
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
