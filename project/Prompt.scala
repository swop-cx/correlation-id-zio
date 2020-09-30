import sbt.{ Project, State }

object Prompt {

  def format(ansiColor: String): State => String = { state ⇒
    s"[${ansiColor}%s${scala.Console.RESET}] λ ".format {
      Project.extract(state).getOpt(sbt.Keys.name).getOrElse {
        Project.extract(state).currentProject.id
      }
    }
  }

}
