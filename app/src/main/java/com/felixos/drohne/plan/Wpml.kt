package com.felixos.drohne.plan

import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Festes Programm: rechnet eine [Mission] in eine DJI-KMZ-Datei (WPML 1.0.6) um.
 * Aufbau: wpmz/template.kml (Planungsdaten) und wpmz/waylines.wpml (Ausführungsdaten).
 * Kein KI-Anteil: gleiche Mission ergibt immer die gleiche Datei.
 */
/** Stellschrauben für die Datei-Erzeugung, um DJIs Prüfer-Meldungen einzugrenzen. */
data class WpmlOptions(
    /** Immer wpml:executeRCLostAction=goBack mitschreiben, auch bei goContinue. */
    val alwaysRcLostAction: Boolean = false,
    /** Keine Aktionen (Gimbal/Foto/Hover) an den Wegpunkten. */
    val noActions: Boolean = false,
    /** WPML-Namensraum-Version, z. B. "1.0.6" oder "1.0.0". */
    val version: String = "1.0.6",
    /** Gerätekennung überschreiben (null = aus der Mission). */
    val droneEnum: Int? = null,
    /** payloadInfo wie im DJI-Beispiel mitschreiben. */
    val payloadInfo: Boolean = false,
)

object Wpml {
    private var opt = WpmlOptions()
    private val NS get() = """xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="http://www.dji.com/wpmz/${opt.version}""""

    private fun f(v: Double, digits: Int = 6) = String.format(Locale.US, "%.${digits}f", v)

    private fun missionConfig(m: Mission): String = """
    <wpml:missionConfig>
      <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>
      <wpml:finishAction>goHome</wpml:finishAction>
      <wpml:exitOnRCLost>${if (m.rcLost == RcLost.CONTINUE) "goContinue" else "executeLostAction"}</wpml:exitOnRCLost>
      ${if (m.rcLost == RcLost.GO_HOME || opt.alwaysRcLostAction) "<wpml:executeRCLostAction>goBack</wpml:executeRCLostAction>" else ""}
      <wpml:takeOffSecurityHeight>${f(m.takeOffSecurityHeightM, 1)}</wpml:takeOffSecurityHeight>
      <wpml:globalTransitionalSpeed>${f(m.speedMs, 1)}</wpml:globalTransitionalSpeed>
      <wpml:droneInfo>
        <wpml:droneEnumValue>${opt.droneEnum ?: m.droneEnum}</wpml:droneEnumValue>
        <wpml:droneSubEnumValue>${m.droneSubEnum}</wpml:droneSubEnumValue>
      </wpml:droneInfo>${if (opt.payloadInfo) """
      <wpml:payloadInfo>
        <wpml:payloadEnumValue>0</wpml:payloadEnumValue>
        <wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue>
        <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
      </wpml:payloadInfo>""" else ""}
    </wpml:missionConfig>"""

    private fun heading() = """
        <wpml:waypointHeadingParam>
          <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>
          <wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle>
          <wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint>
          <wpml:waypointHeadingAngleEnable>0</wpml:waypointHeadingAngleEnable>
          <wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode>
        </wpml:waypointHeadingParam>
        <wpml:waypointTurnParam>
          <wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode>
          <wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist>
        </wpml:waypointTurnParam>
        <wpml:useStraightLine>1</wpml:useStraightLine>"""

    private fun actions(i: Int, w: Waypoint, groupId: Int): String {
        val acts = buildList {
            add("""<wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>
            <wpml:actionActuatorFuncParam>
              <wpml:gimbalHeadingYawBase>aircraft</wpml:gimbalHeadingYawBase>
              <wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>
              <wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>
              <wpml:gimbalPitchRotateAngle>${f(w.gimbalPitchDeg, 1)}</wpml:gimbalPitchRotateAngle>
              <wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable>
              <wpml:gimbalRollRotateAngle>0</wpml:gimbalRollRotateAngle>
              <wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable>
              <wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle>
              <wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable>
              <wpml:gimbalRotateTime>0</wpml:gimbalRotateTime>
              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
            </wpml:actionActuatorFuncParam>""")
            if (w.hoverSec > 0) add("""<wpml:actionActuatorFunc>hover</wpml:actionActuatorFunc>
            <wpml:actionActuatorFuncParam><wpml:hoverTime>${w.hoverSec}</wpml:hoverTime></wpml:actionActuatorFuncParam>""")
            if (w.takePhoto) add("""<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>
            <wpml:actionActuatorFuncParam>
              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
              <wpml:fileSuffix>wp$i</wpml:fileSuffix>
            </wpml:actionActuatorFuncParam>""")
        }
        return """
        <wpml:actionGroup>
          <wpml:actionGroupId>$groupId</wpml:actionGroupId>
          <wpml:actionGroupStartIndex>$i</wpml:actionGroupStartIndex>
          <wpml:actionGroupEndIndex>$i</wpml:actionGroupEndIndex>
          <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
          <wpml:actionTrigger><wpml:actionTriggerType>reachPoint</wpml:actionTriggerType></wpml:actionTrigger>
""" + acts.mapIndexed { a, body -> "          <wpml:action>\n            <wpml:actionId>$a</wpml:actionId>\n            $body\n          </wpml:action>" }
            .joinToString("\n") + "\n        </wpml:actionGroup>"
    }

    private fun placemark(i: Int, w: Waypoint, m: Mission, execution: Boolean): String = """
      <Placemark>
        <Point><coordinates>${f(w.pos.lon)},${f(w.pos.lat)}</coordinates></Point>
        <wpml:index>$i</wpml:index>
        ${if (execution) "<wpml:executeHeight>${f(w.heightM, 1)}</wpml:executeHeight>"
          else "<wpml:height>${f(w.heightM, 1)}</wpml:height>\n        <wpml:ellipsoidHeight>${f(w.heightM, 1)}</wpml:ellipsoidHeight>\n        <wpml:useGlobalHeight>0</wpml:useGlobalHeight>\n        <wpml:useGlobalSpeed>1</wpml:useGlobalSpeed>\n        <wpml:useGlobalHeadingParam>1</wpml:useGlobalHeadingParam>\n        <wpml:useGlobalTurnParam>1</wpml:useGlobalTurnParam>"}
        <wpml:waypointSpeed>${f(m.speedMs, 1)}</wpml:waypointSpeed>${if (execution) heading() else ""}${if (opt.noActions) "" else actions(i, w, i)}
      </Placemark>"""

    fun templateKml(m: Mission, nowMs: Long): String = """<?xml version="1.0" encoding="UTF-8"?>
<kml $NS>
  <Document>
    <wpml:author>Felix Drohne</wpml:author>
    <wpml:createTime>$nowMs</wpml:createTime>
    <wpml:updateTime>$nowMs</wpml:updateTime>${missionConfig(m)}
    <Folder>
      <wpml:templateType>waypoint</wpml:templateType>
      <wpml:templateId>0</wpml:templateId>
      <wpml:waylineCoordinateSysParam>
        <wpml:coordinateMode>WGS84</wpml:coordinateMode>
        <wpml:heightMode>relativeToStartPoint</wpml:heightMode>
      </wpml:waylineCoordinateSysParam>
      <wpml:autoFlightSpeed>${f(m.speedMs, 1)}</wpml:autoFlightSpeed>
      <wpml:globalHeight>${f(m.waypoints.first().heightM, 1)}</wpml:globalHeight>
      <wpml:caliFlightEnable>0</wpml:caliFlightEnable>
      <wpml:gimbalPitchMode>usePointSetting</wpml:gimbalPitchMode>
      <wpml:globalWaypointHeadingParam>
        <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>
        <wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle>
        <wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint>
        <wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode>
      </wpml:globalWaypointHeadingParam>
      <wpml:globalWaypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:globalWaypointTurnMode>
      <wpml:globalUseStraightLine>1</wpml:globalUseStraightLine>""" +
        m.waypoints.mapIndexed { i, w -> placemark(i, w, m, execution = false) }.joinToString("") + """
    </Folder>
  </Document>
</kml>
"""

    fun waylinesWpml(m: Mission): String = """<?xml version="1.0" encoding="UTF-8"?>
<kml $NS>
  <Document>${missionConfig(m)}
    <Folder>
      <wpml:templateId>0</wpml:templateId>
      <wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>
      <wpml:waylineId>0</wpml:waylineId>
      <wpml:distance>${f(m.lengthM, 1)}</wpml:distance>
      <wpml:duration>${f(m.durationSec, 1)}</wpml:duration>
      <wpml:autoFlightSpeed>${f(m.speedMs, 1)}</wpml:autoFlightSpeed>""" +
        m.waypoints.mapIndexed { i, w -> placemark(i, w, m, execution = true) }.joinToString("") + """
    </Folder>
  </Document>
</kml>
"""

    /** Schreibt die KMZ-Datei und gibt sie zurück. */
    fun writeKmz(m: Mission, out: File, nowMs: Long = System.currentTimeMillis(), options: WpmlOptions = WpmlOptions()): File {
        opt = options
        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/template.kml")); zip.write(templateKml(m, nowMs).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("wpmz/waylines.wpml")); zip.write(waylinesWpml(m).toByteArray()); zip.closeEntry()
        }
        return out
    }
}
