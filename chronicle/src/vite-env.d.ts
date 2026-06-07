/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent
  export default component
}

declare module '*.module.css' {
  const classes: Record<string, string>
  export default classes
}

/* Phosphor web font: the `./regular` subpath export maps to a side-effect CSS
   file (via the package's exports map) whose specifier has no `.css` suffix,
   so it needs an explicit ambient declaration. */
declare module '@phosphor-icons/web/regular'
