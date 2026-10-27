import mill._
import mill.scalalib._

object rv extends ScalaModule {
  def scalaVersion = "2.13.17"
  def millSourcePath = os.pwd
  def scalacOptions = Seq("-language:reflectiveCalls", "-deprecation", "-feature", "-Xcheckinit", "-Ymacro-annotations")
  def ivyDeps = Agg(ivy"org.chipsalliance::chisel:7.3.0")
  def scalacPluginIvyDeps = Agg(ivy"org.chipsalliance:::chisel-plugin:7.3.0")
}
