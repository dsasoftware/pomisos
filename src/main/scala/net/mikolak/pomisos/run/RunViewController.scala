package net.mikolak.pomisos.run

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneOffset}

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props}
import net.mikolak.pomisos.audio.SamplePlayer
import net.mikolak.pomisos.data.{DB, Pomodoro, PomodoroRun, TimerPeriod}
import net.mikolak.pomisos.graphics.{FontAwesomeGlyphs, GlyphRotators}
import net.mikolak.pomisos.prefs.PreferenceDao
import net.mikolak.pomisos.process.{OnBreak, OnPomodoro, ProcessManager}
import net.mikolak.pomisos.quality.QualityService
import net.mikolak.pomisos.utils.Notifications
import org.controlsfx.glyphfont.FontAwesome
import gremlin.scala._
import net.mikolak.pomisos.dependencies.ActorRefContainer
import net.mikolak.pomisos.prefs.NotifySound.NotificationSound

import scala.concurrent.duration._
import scala.language.postfixOps
import scalafx.Includes._
import scalafx.application.Platform
import scalafx.beans.binding.{Bindings, BooleanBinding}
import scalafx.beans.property.{LongProperty, ObjectProperty}
import scalafx.event.ActionEvent
import scalafx.scene.control.{Button, Slider}
import scalafx.scene.layout.VBox
import scalafx.scene.text.Text
import scalafxml.core.macros.sfxml

trait RunView {

  def runningPomodoro: ObjectProperty[Option[Pomodoro]]

  def isRunning: BooleanBinding

}

@sfxml
class RunViewController(val currentPomodoroDisplay: Text,
                        val timerText: Text,
                        val stopButton: Button,
                        val pauseResumeButton: Button,
                        val qualityAppQueryView: VBox,
                        val qualitySlider: Slider,
                        val actorSystem: ActorSystem,
                        notifications: Notifications,
                        processMan: ActorRefContainer[ProcessManager],
                        qualityService: QualityService,
                        db: DB,
                        preferenceDao: PreferenceDao,
                        glyphs: FontAwesomeGlyphs,
                        glyphRotators: GlyphRotators)
    extends RunView {

  stopButton.graphic = glyphs(FontAwesome.Glyph.STOP)
  val pauseResumeGlyphs = glyphRotators(FontAwesome.Glyph.PAUSE, FontAwesome.Glyph.PLAY)
  pauseResumeButton.graphic <== pauseResumeGlyphs.value

  lazy val runningPeriod: ObjectProperty[Option[TimerPeriod]] = ObjectProperty(None)
  lazy val runningPomodoro                                    = ObjectProperty[Option[Pomodoro]](None)

  lazy val remainingSeconds = LongProperty(0)
  lazy val isRunning        = new BooleanBinding(remainingSeconds =!= 0)

  private var timerStack      = List.empty[TimerPeriod]
  private var pomodoroCounter = 0
  val BreakText               = "Break"

  qualityAppQueryView.visible <== Bindings.createBooleanBinding(() => currentPomodoroDisplay.text.value == BreakText,
                                                                currentPomodoroDisplay.text)

  def updateRunning(item: Option[TimerPeriod]) = {
    runningPeriod.value = item
    item.foreach { pom =>
      remainingSeconds.value = pom.duration.toSeconds
      timerActor ! Start
    }
  }

  def stopPomodoro(event: ActionEvent) =
    timerActor ! Stop

  def pauseResume(event: ActionEvent) = {
    pauseResumeGlyphs.rotate()
    timerActor ! PauseResume
  }

  currentPomodoroDisplay.text <== Bindings.createStringBinding(() => runningPeriod.value.map(_.name).getOrElse(""),
                                                               runningPeriod)

  lazy val formatter = DateTimeFormatter.ofPattern("mm:ss")
  timerText.text <== Bindings.createStringBinding(
    () => formatter.format(LocalDateTime.ofEpochSecond(remainingSeconds.toLong, 0, ZoneOffset.UTC)),
    remainingSeconds)

  private lazy val timerActor = actorSystem.actorOf(Props(classOf[TimerActor], remainingSeconds, preferenceDao))

  remainingSeconds.onChange((_, _, currentSeconds) => {
    if (currentSeconds.intValue() == 0) {
      popStack()
    }

    if (!isRunning.value && timerStack.isEmpty) {
      runningPomodoro.value = None
      notifications.show("Break done, pick a new Pomodoro!")
      playNotifyIfNeeded()
      qualityService.handleNewPomodoroQuality(qualitySlider.value.value.toInt)
    }
  })

  isRunning.onChange((_, _, newVal) => {
    for (period <- runningPeriod.value if newVal) {
      val onBreak = period.name == BreakText
      val text    = if (onBreak) "You're on a break" else period.name

      if (onBreak) {
        processMan.get ! OnBreak
      } else {
        pomodoroCounter += 1
        processMan.get ! OnPomodoro
      }

      notifications.show(text)
      if (onBreak) {
        playNotifyIfNeeded()
      }
    }
  })

  runningPomodoro.onChange((obs, _, newVal) =>
    for (newPomodoro <- newVal if newVal.isDefined) {
      println(s"Running Pomodoro $newPomodoro")
      val currentPrefs = preferenceDao.get()
      val breakDuration =
        if (pomodoroCounter > 1 && (pomodoroCounter - 1) % currentPrefs.length.pomodorosForLongBreak == 0)
          currentPrefs.length.longBreak
        else currentPrefs.length.shortBreak

      timerStack ::= TimerPeriod(None, BreakText, breakDuration)
      timerStack ::= TimerPeriod(newPomodoro.id, newPomodoro.name, currentPrefs.length.pomodoro)
      updateRunning(timerStack.headOption)
  })

  private def popStack() =
    timerStack match {
      case ranPomodoro :: tail =>
        storeRun(ranPomodoro)
        timerStack = tail
        doRun()
      case _ => //do nothing
    }

  private def doRun() =
    updateRunning(timerStack.headOption)

  private def storeRun(period: TimerPeriod) =
    for {
      pomId    <- period.id
      pomodoro <- db().V.hasLabel[Pomodoro].hasId(pomId).headOption()
    } {
      val run = db().addVertex(PomodoroRun(Instant.now(), period.duration))
      pomodoro.addEdge("ranAt", run)
    }

  private def playNotifyIfNeeded() =
    for (sound <- preferenceDao.get().audio.notificationSound) {
      val player = new SamplePlayer(soundFor(sound))
      player.play()
    }

  private def soundFor(notifySound: NotificationSound) =
    s"/net/mikolak/pomisos/audio/${notifySound.toString.toLowerCase.replace(" ", "_")}.wav"

}

case class TimerActor(remainingSeconds: LongProperty, preferenceDao: PreferenceDao) extends Actor {
  var currentSchedule: Option[Cancellable] = None

  var ticker: Option[ActorRef] = None

  override def receive = {
    case Start =>
      toggleScheduler()
      if (preferenceDao.get().audio.playTick) {
        ticker = Some(context.actorOf(Props[TickPlayer]))
      }
    case Stop =>
      handleUpdate(0)
      ticker.foreach { t =>
        t ! Stop
      }
    case Tick =>
      handleUpdate(remainingSeconds.value - 1)
      ticker.foreach(_ ! Tick)
    case PauseResume =>
      toggleScheduler()
  }

  private def toggleScheduler() =
    currentSchedule match {
      case Some(scheduler) =>
        scheduler.cancel()
        currentSchedule = None
      case None =>
        import context.dispatcher
        currentSchedule = Some(context.system.scheduler.schedule(1 seconds, 1 second, self, Tick))
    }

  private def handleUpdate(secs: Long) = {
    if (secs == 0) {
      toggleScheduler()
    }
    Platform.runLater(remainingSeconds.value = secs)
  }
}

class TickPlayer extends Actor {
  lazy val ticker = new SamplePlayer("/net/mikolak/pomisos/audio/clock_tick.wav")

  override def receive = {
    case Tick =>
      ticker.play()
    case Stop =>
      ticker.stop()
  }
}

case object Tick

case object Start

case object Stop

case object PauseResume
