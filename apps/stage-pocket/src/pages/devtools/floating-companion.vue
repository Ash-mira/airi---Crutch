<script setup lang="ts">
import { Button, FieldInput } from '@proj-airi/ui'
import { onMounted, ref } from 'vue'
import { toast } from 'vue-sonner'

import { FloatingCompanion, isFloatingCompanionSupported } from '../../libs/floating-companion'

const supported = isFloatingCompanionSupported()

const overlayGranted = ref(false)
const notifGranted = ref(false)
const running = ref(false)
const busy = ref(false)
const startError = ref<string | null>(null)

async function refresh() {
  if (!supported) return
  try {
    overlayGranted.value = (await FloatingCompanion.hasOverlayPermission()).granted
    running.value = (await FloatingCompanion.isRunning()).running
  } catch (e) {
    console.error('Failed to refresh floating companion status:', e)
  }
}

async function requestOverlay() {
  if (!supported) return
  busy.value = true
  try {
    const result = await FloatingCompanion.requestOverlayPermission()
    overlayGranted.value = result.granted
    if (result.granted) {
      toast.success('Overlay permission granted')
    } else {
      toast.warning('Overlay permission not granted; open settings and toggle "Appear on top"')
    }
  } catch (e: any) {
    toast.error(e?.message ?? 'Failed to request overlay permission')
  } finally {
    busy.value = false
  }
}

async function requestNotification() {
  if (!supported) return
  busy.value = true
  try {
    const result = await FloatingCompanion.requestNotificationPermission()
    notifGranted.value = result.granted
    if (result.granted) {
      toast.success('Notification permission granted')
    } else {
      toast.warning('Notification permission denied')
    }
  } catch (e: any) {
    toast.error(e?.message ?? 'Failed to request notification permission')
  } finally {
    busy.value = false
  }
}

async function startCompanion() {
  if (!supported) return
  startError.value = null
  busy.value = true
  try {
    const result = await FloatingCompanion.start()
    running.value = result.started
    if (result.alreadyRunning) {
      toast.info('Floating companion already running')
    } else {
      toast.success('Floating companion started')
    }
  } catch (e: any) {
    const message = e?.message ?? 'Failed to start floating companion'
    startError.value = message
    if (e?.code === 'OVERLAY_PERMISSION_MISSING') {
      toast.error('Overlay permission required — grant "Appear on top" first', { duration: 8000 })
    } else {
      toast.error(message)
    }
  } finally {
    busy.value = false
  }
}

async function stopCompanion() {
  if (!supported) return
  busy.value = true
  try {
    await FloatingCompanion.stop()
    running.value = false
    startError.value = null
    toast.success('Floating companion stopped')
  } catch (e: any) {
    toast.error(e?.message ?? 'Failed to stop floating companion')
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  if (supported) {
    await refresh()
    const result = await FloatingCompanion.requestNotificationPermission()
    notifGranted.value = result.granted
  }
})
</script>

<template>
  <div class="h-[calc(100dvh-40px)] p-4">
    <div class="max-w-xl mx-auto space-y-6">
      <div class="rounded-lg bg-neutral-100 p-4 dark:bg-neutral-900">
        <h2 class="mb-4 text-lg font-semibold">Floating Companion MVP</h2>

        <div v-if="!supported" class="text-center text-neutral-500 dark:text-neutral-400 py-8">
          <p class="text-xl mb-2">📱 Native Android only</p>
          <p>This control surface is available only on Android Capacitor builds.</p>
        </div>

        <div v-else class="space-y-4">
          <!-- Status grid -->
          <div class="grid gap-3 sm:grid-cols-2">
            <FieldInput
              label="Overlay permission"
              :value="overlayGranted ? 'Granted' : 'Denied'"
              readonly
              description="Required: Settings → Apps → AIRI → Appear on top"
            />
            <FieldInput
              label="Notification permission"
              :value="notifGranted ? 'Granted' : 'Denied'"
              readonly
              description="Android 13+; required for the foreground-service Stop action"
            />
            <FieldInput
              label="Service running"
              :value="running ? 'Yes' : 'No'"
              readonly
              description="Foreground service state (idempotent start/stop)"
            />
          </div>

          <!-- Start error highlight -->
          <div v-if="startError" class="rounded border border-red-500 bg-red-500/10 p-3 text-red-700 dark:text-red-300 text-sm">
            <strong>Start failed:</strong> {{ startError }}
            <div v-if="startError.includes('OVERLAY_PERMISSION_MISSING')" class="mt-2">
              <Button @click="requestOverlay" :disabled="busy" variant="primary" size="sm">
                Open "Appear on top" settings
              </Button>
            </div>
          </div>

          <!-- Actions -->
          <div class="flex flex-wrap gap-2">
            <Button @click="requestOverlay" :disabled="busy || overlayGranted" variant="ghost">
              Request Overlay Permission
            </Button>
            <Button @click="requestNotification" :disabled="busy || notifGranted" variant="ghost">
              Request Notification Permission
            </Button>
            <Button @click="startCompanion" :disabled="busy || running || !overlayGranted" variant="primary">
              Start Companion
            </Button>
            <Button @click="stopCompanion" :disabled="busy || !running" variant="ghost">
              Stop Companion
            </Button>
            <Button @click="refresh" :disabled="busy" variant="ghost" icon="lucide:refresh-cw" />
          </div>

          <div class="text-xs text-neutral-500 dark:text-neutral-400">
            <p>Tap the floating button (launcher icon) to reopen AIRI. Use the notification "Stop" action or the button above to tear it down.</p>
            <p class="mt-1">This MVP renders a native test view only; the AIRI avatar is not rendered in the overlay yet.</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<route lang="yaml">
meta:
  layout: plain
</route>