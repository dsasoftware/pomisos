package net.mikolak.pomisos.prefs

import com.orientechnologies.orient.core.id.ORecordId
import gremlin.scala.ScalaGraph
import net.mikolak.pomisos.crud.{AddNew, AddNewController, Idable}

import scalafx.beans.property.ReadOnlyObjectProperty
import scalafx.collections.ObservableBuffer
import scalafx.scene.control._
import scalafx.scene.control.cell.TextFieldListCell
import scalafx.scene.input.{KeyCode, KeyEvent}
import scalafxml.core.macros.{nested, sfxml}
import scalafx.Includes._
import gremlin.scala._
import net.mikolak.pomisos.data.Id
import net.mikolak.pomisos.prefs.Command.{FullCommandSpec, IdKey, SpecEither, WithId}
import shapeless.{:+:, CNil, Coproduct, Generic, Inl, Inr, Lens, Poly, Poly1, lens}
import net.mikolak.pomisos.utils.Implicits._
import org.apache.tinkerpop.gremlin.structure.T
import shapeless.PolyDefns.{->, ~>}

import scalafx.event.ActionEvent
import scalafx.scene.layout.VBox
import scalafx.util.StringConverter
import Command.specToIds

@sfxml
class CommandController(@nested[AddNewController] addNewCmdController: AddNew,
                        commandType: ToggleGroup,
                        val cmdList: ListView[FullCommandSpec],
                        db: () => ScalaGraph,
                        dao: CommandDao,
                        toggleExecution: RadioButton,
                        toggleScript: RadioButton,
                        adminPaneGeneral: VBox,
                        adminPaneDetail: VBox,
                        adminViewExecution: VBox,
                        executionCommand: TextField,
                        adminViewScript: VBox,
                        scriptStartup: TextArea,
                        scriptStop: TextArea,
                        saveButton: Button) {

  import CommandUi._

  lazy val cmdSelected: ReadOnlyObjectProperty[FullCommandSpec] =
    cmdList.getSelectionModel.selectedItemProperty

  lazy val cmdSelectedIndex = cmdList.getSelectionModel.selectedIndexProperty()

  lazy val cmds = ObservableBuffer(dao.getAll().toList)
  cmdList.setItems(cmds)

  object withConfig extends Poly1 {
    implicit def forScript =
      at[Script](
        _ -> List((lens[Script] >> 'onPomodoro, scriptStartup: TextInputControl),
                  (lens[Script] >> 'onBreak, scriptStop: TextInputControl)))
    implicit def forExecution = at[Execution](_ -> List((lens[Execution] >> 'cmd, executionCommand: TextInputControl)))
  }

  adminPaneGeneral.disable <== cmdSelected.map(_ == null).toBoolean
  adminPaneDetail.visible <== !adminPaneGeneral.disable

  //just need this one
  adminViewExecution.visible <== cmdSelected
    .map(s => s != null && s._2.select[Execution].nonEmpty)
    .toBoolean
  adminViewScript.visible <== !adminViewExecution.visible

  cmdSelected.onChange((_, _, newVal) => {

    newVal match {
      case (_, Inl(_: Execution)) => {

        toggleExecution.selected = true
      }
      case (_, Inr(Inl(_: Script))) => {
        toggleScript.selected = true
      }
      case _ => //fallthrough for null
    }

    loadValues()
  })

  commandType.selectedToggle.onChange((_, _, newVal) => {
    val isExecution    = cmdSelected.value._2.select[Execution].nonEmpty
    val needsSwitching = ((newVal == toggleScript.delegate) && isExecution) || (newVal == toggleExecution.delegate && !isExecution)

    if (needsSwitching) {
      val newSpec       = dao.convertToOther(cmdSelected.value)
      val selectedIndex = cmdList.getSelectionModel.getSelectedIndices.head
      cmds.update(selectedIndex, newSpec)

      //needs to reselect
      cmdList.getSelectionModel.select(selectedIndex)
    }
  })

  cmds.onChange(observerFor[FullCommandSpec](cmds, dao))

  cmdList.cellFactory = TextFieldListCell.forListView(new StringConverter[FullCommandSpec] {
    override def fromString(string: String) = ???

    override def toString(t: (Command, SpecEither)) = t._1.name.getOrElse("")
  })
  cmdList.editable = false

  def listKeyPressed(event: KeyEvent): Unit =
    if (event.code == KeyCode.Delete && Option(cmdSelected.value).isDefined) {
      cmdList.items.get().remove(cmdList.getSelectionModel.getSelectedIndex)
    }

  cmdList.getSelectionModel.setSelectionMode(SelectionMode.Single)

  addNewCmdController.newName.onChange((obs, _, newItemOpt) =>
    for (newItem <- newItemOpt if !newItem.isEmpty) {
      val newCmd = dao.save(Command(None, Some(newItem)) -> Coproduct[SpecEither](Execution(None, Some(newItem))))

      cmdList.items.get().add(newCmd)

      cmdList.getSelectionModel.selectLast()
  })

  def saveSpec(actionEvent: ActionEvent) = {
    import shapeless._
    import ops.coproduct._

    val (curCommand, curSpec) = cmdSelected.value

    //TODO: reduce boilerplate 1. test with Generic[CommandSpec] 2. Ask on SO
    object applyValues extends Poly1 {
      private def allCases[T <: CommandSpec](arg: SpecWithFields[T]) = arg match {
        case (on, list) =>
          list.foldLeft(on) { case (current, (lensToUse, field)) => lensToUse.set(current)(Option(field.text.value)) }
      }

      implicit def caseScript    = at[SpecWithFields[Script]](allCases)
      implicit def caseExecution = at[SpecWithFields[Execution]](allCases)
    }

    val newSpec = curSpec.map(withConfig).map(applyValues)
    cmds.update(cmdSelectedIndex.intValue(), (curCommand, newSpec))
  }

  private def loadValues(): Unit = {
    import shapeless._
    import ops.coproduct._

    val (_, curSpec) = cmdSelected.value

    //TODO: reduce boilerplate 1. test with Generic[CommandSpec] 2. Ask on SO
    object fillText extends Poly1 {
      private def allCases[T <: CommandSpec](arg: SpecWithFields[T]) = arg match {
        case (on, list) =>
          list.foldLeft(on) {
            case (current, (lensToUse, field)) => {
              field.text.value = lensToUse.get(current).getOrElse("")
              current
            }
          }
      }

      implicit def caseScript    = at[SpecWithFields[Script]](allCases)
      implicit def caseExecution = at[SpecWithFields[Execution]](allCases)
    }

    curSpec.map(withConfig).map(fillText)
  }
}

object CommandUi {
  type FieldList[T]                     = List[(Lens[T, Option[String]], TextInputControl)]
  type SpecWithFields[T <: CommandSpec] = (T, FieldList[T])
}

object Command {

  /*
   * Coproduct for spec types
   */
  type SpecEither = Execution :+: Script :+: CNil

  type FullCommandSpec = (Command, SpecEither)

  val SpecEdge = "specced"

  type IdStandard = ORecordId

  type IdKey = Option[IdStandard]

  trait WithId {
    def id: IdKey
  }

  implicit val specToIds: Idable[FullCommandSpec] = {
    import shapeless._
    import poly._
    import ops.coproduct._

    object idOfSpec extends Poly1 {
      implicit def caseScript    = at[Script](_.id)
      implicit def caseExecution = at[Execution](_.id)

    }

    new Idable[FullCommandSpec] {
      override def idsOf(spec: FullCommandSpec): Seq[IdKey] = spec match {
        case (command, spec) => Seq(command.id, spec.fold(idOfSpec))
      }
    }
  }

}

case class Command(@id id: IdKey, name: Option[String]) extends WithId

sealed trait CommandSpec extends Product with Serializable with WithId

case class Execution(id: IdKey, cmd: Option[String]) extends CommandSpec

case class Script(id: IdKey, onPomodoro: Option[String], onBreak: Option[String]) extends CommandSpec
