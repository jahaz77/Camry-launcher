package com.camry.launcher

import android.service.notification.NotificationListenerService

/**
 * Servicio vacío: Android exige un "lector de notificaciones" habilitado
 * para que el launcher pueda ver qué música se está reproduciendo.
 */
class MediaListener : NotificationListenerService()
