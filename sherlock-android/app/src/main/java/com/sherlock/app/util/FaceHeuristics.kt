package com.sherlock.app.util

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.hypot

/**
 * Rough, on-device heuristics derived from ML Kit face landmarks/classifiers.
 * These are NOT validated biometric models - results are approximate estimates only.
 */
object FaceHeuristics {

    fun estimateAgeRange(face: Face): String {
        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val width = face.boundingBox.width().toFloat()
        if (left == null || right == null || width <= 0f) return "לא ניתן להעריך"
        val eyeDistance = hypot((right.x - left.x).toDouble(), (right.y - left.y).toDouble()).toFloat()
        val ratio = eyeDistance / width
        return when {
            ratio > 0.46f -> "ילד/ה (הערכה גסה, ~0-12)"
            ratio > 0.40f -> "מתבגר/ת (הערכה גסה, ~13-19)"
            ratio > 0.34f -> "מבוגר/ת צעיר/ה (הערכה גסה, ~20-40)"
            else -> "מבוגר/ת (הערכה גסה, ~40+)"
        }
    }

    fun estimateExpression(face: Face): String {
        val smile = face.smilingProbability ?: -1f
        val leftEye = face.leftEyeOpenProbability ?: -1f
        val rightEye = face.rightEyeOpenProbability ?: -1f
        if (smile < 0f) return "לא ניתן לקבוע (סיווג לא זמין)"
        return when {
            smile >= 0.7f -> "מחייך/ת בבירור"
            smile >= 0.4f -> "חיוך קל"
            leftEye in 0f..0.3f && rightEye in 0f..0.3f -> "עיניים עצומות"
            else -> "הבעה ניטרלית"
        }
    }
}
