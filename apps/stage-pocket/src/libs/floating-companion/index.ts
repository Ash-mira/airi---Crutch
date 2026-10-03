import { Capacitor, registerPlugin } from '@capacitor/core'

export interface OverlayPermissionResult {
  granted: boolean
}

export interface NotificationPermissionResult {
  granted: boolean
}

export interface CompanionRunningResult {
  running: boolean
}

export interface CompanionStartResult {
  started: boolean
  alreadyRunning: boolean
}

export interface CompanionStopResult {
  stopped: boolean
}

export interface FloatingCompanionPlugin {
  isSupported: () => Promise<{ supported: boolean }>
  hasOverlayPermission: () => Promise<OverlayPermissionResult>
  requestOverlayPermission: () => Promise<OverlayPermissionResult>
  requestNotificationPermission: () => Promise<NotificationPermissionResult>
  isRunning: () => Promise<CompanionRunningResult>
  start: () => Promise<CompanionStartResult>
  stop: () => Promise<CompanionStopResult>
}

/**
 * Native Android floating companion MVP bridge.
 *
 * The native plugin only exists on Android; callers must gate on
 * {@link isFloatingCompanionSupported} before invoking methods.
 */
export const FloatingCompanion = registerPlugin<FloatingCompanionPlugin>('FloatingCompanion')

export function isFloatingCompanionSupported(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'
}
