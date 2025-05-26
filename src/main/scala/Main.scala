import org.chipsalliance.cde.config.Config
import freechips.rocketchip.subsystem._
import freechips.rocketchip.system._
import freechips.rocketchip.rocket._
import circt.stage
import boom.v3.common._
import freechips.rocketchip.diplomacy.Main
import chisel3.RawModule
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.stage.phases.{Elaborate, Convert}
import firrtl.AnnotationSeq
import firrtl.options.TargetDirAnnotation
import freechips.rocketchip.diplomacy.LazyModule
import org.chipsalliance.cde.config.{Config, Parameters}
import mainargs._
import scala.reflect.runtime.universe._
import scala.reflect.runtime.currentMirror
import java.nio.file.{Files, Paths}
import java.io.{InputStream, FileOutputStream}
import freechips.rocketchip.rocket.WithNHugeCores
import freechips.rocketchip.devices.tilelink.BootROMParams
import freechips.rocketchip.devices.tilelink.BootROMLocated

class OverrideBootromLocation(contentFileName: String) extends Config((site, here, up) => {
  case BootROMLocated(InSubsystem) => Some(BootROMParams(contentFileName=contentFileName))
})

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
    dir_rel: String,
    @arg(name = "core", doc = "which core to use (TinyRocket, SmallRocket, MedRocket, BigRocket, HugeRocket, SmallBoom, MediumBoom, LargeBoom, MegaBoom, GigaBoom)")
    core: String = "SmallRocket",
    @arg(name = "ncores", doc = "number of cores")
    ncores: Int = 1
  ) = {
    val config = core match {
      case "TinyRocket"  => new Config(new With1TinyCore ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "SmallRocket" => new Config(new WithNSmallCores(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "MedRocket"   => new Config(new WithNMedCores(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "BigRocket"   => new Config(new WithNBigCores(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "HugeRocket"  => new Config(new WithNHugeCores(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "SmallBoom"   => new Config(new WithNSmallBooms(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "MediumBoom"  => new Config(new WithNMediumBooms(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "LargeBoom"   => new Config(new WithNLargeBooms(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "MegaBoom"    => new Config(new WithNMegaBooms(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "GigaBoom"    => new Config(new WithNGigaBooms(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case _ => throw new Exception(s"Unknown core type: $core")
    }
    val config_name = s"$core-$ncores"
    val dir = Paths.get(dir_rel).toAbsolutePath().toString
    val top = "freechips.rocketchip.system.TestHarness"
    var topName: String = null
    val gen = () => new TestHarness()(config)
    val annos = Seq(
      new Elaborate,
      new Convert
    ).foldLeft(
      Seq(
        TargetDirAnnotation(dir),
        ChiselGeneratorAnnotation(() => gen())
      ): AnnotationSeq
    ) { case (annos, phase) => phase.transform(annos) }
      .flatMap {
        case firrtl.stage.FirrtlCircuitAnnotation(circuit) =>
          topName = circuit.main
          os.write(os.Path(dir) / s"${circuit.main}.fir", circuit.serialize)
          None
        case _: chisel3.stage.ChiselCircuitAnnotation => None
        case _: chisel3.stage.DesignAnnotation[_] => None
        case a => Some(a)
      }
    os.write(os.Path(dir) / s"$topName.anno.json", firrtl.annotations.JsonProtocol.serialize(annos))
    freechips.rocketchip.util.ElaborationArtefacts.files.foreach{ case (ext, contents) => os.write.over(os.Path(dir) / s"${config_name}.${ext}", contents()) }
  }
  ParserForMethods(this).runOrThrow(args)
}
