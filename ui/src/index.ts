import { definePlugin } from '@halo-dev/ui-shared'
import { consoleRoutes } from '@/console/routes'
import { ucRoutes } from '@/uc/routes'
import '@/styles/tokens.css'

export default definePlugin({
  components: {},
  routes: consoleRoutes,
  ucRoutes,
  extensionPoints: {},
})
