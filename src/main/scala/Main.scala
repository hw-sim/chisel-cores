import org.chipsalliance.cde.config.Config
import freechips.rocketchip.subsystem._
import freechips.rocketchip.system._
import freechips.rocketchip.rocket.{WithNBigCores, WithNMedCores, WithNSmallCores, WithRV32, WithFP16, WithHypervisor, With1TinyCore, WithScratchpadsOnly, WithCloneRocketTiles, WithB}
import circt.stage
import boom.v3.common._
import freechips.rocketchip.diplomacy.Main

class SmallBoomConfig  extends Config(new WithNSmallBooms(1)  ++ new WithCoherentBusTopology ++ new BaseConfig)
class MediumBoomConfig extends Config(new WithNMediumBooms(1) ++ new WithCoherentBusTopology ++ new BaseConfig)
class LargeBoomConfig  extends Config(new WithNLargeBooms(1)  ++ new WithCoherentBusTopology ++ new BaseConfig)

import mainargs._
import scala.reflect.runtime.universe._
import scala.reflect.runtime.currentMirror
import java.nio.file.{Files, Paths}

object main extends App {
  def getClassFullName(className: String): Option[String] = {
    try {
      val classSymbol = currentMirror.staticClass(className)
      Some(classSymbol.fullName)
    } catch {
      case _: ScalaReflectionException => None
    }
  }
  @main def elaborate(
    @arg(name = "dir", doc = "output directory")
    dir: String,
    @arg(name = "top", doc = "top Module or LazyModule fullpath")
    top: Option[String],
    @arg(name = "config", doc = "CDE configs")
    config: Seq[String]
  ) = {
    val opt_top = top match {
      case Some(top) => top
      case None => "freechips.rocketchip.system.TestHarness"
    }
    val dir_abs = Paths.get(dir).toAbsolutePath()
    val opt_config = config.map(c => {
      getClassFullName(c) match {
        case Some(name) => name
        case None => getClassFullName(s"freechips.rocketchip.system.$c") match {
          case Some(name) => name
          case None => throw new Exception(s"Class not found: $c")
        }
      }
    })
    if(Files.notExists(dir_abs)) {
      Files.createDirectories(dir_abs)
    } else {
      throw new Exception(s"Outout dir already exists $dir")
    }
    println(s"Config: $opt_config")
    println("Firtool Compile Command:")
    println(s"firtool ${dir}/TestHarness.fir --annotation-file=${dir}/TestHarness.anno.json --disable-annotation-unknown --split-verilog --lowering-options=disallowLocalVariables -o ${dir}/verilog")
    println()
    Main.elaborate(dir_abs.toString(), opt_top, opt_config)
  }
  ParserForMethods(this).runOrExit(args)
}
