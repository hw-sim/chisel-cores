package chiselcores

import org.chipsalliance.cde.config.Config
import freechips.rocketchip.subsystem._
import freechips.rocketchip.system._
import freechips.rocketchip.rocket._
import circt.stage
import boom.v3.common._
import freechips.rocketchip.diplomacy.Main
import chisel3._
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
import freechips.rocketchip.devices.debug.Debug
import freechips.rocketchip.util.AsyncResetReg

class OverrideBootromLocation(contentFileName: String) extends Config((site, here, up) => {
  case BootROMLocated(InSubsystem) => Some(BootROMParams(contentFileName=contentFileName))
})

class TestHarnessNoDTM()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val success = Output(Bool())
  })

  val ldut = LazyModule(new ExampleRocketSystem)
  val dut = Module(ldut.module)

  ldut.io_clocks.get.elements.values.foreach(_.clock := clock)
  // Allow the debug ndreset to reset the dut, but not until the initial reset has completed
  //val dut_reset = (reset.asBool | ldut.debug.map { debug => AsyncResetReg(debug.ndreset) }.getOrElse(false.B)).asBool
  val dut_reset = reset.asBool
  ldut.io_clocks.get.elements.values.foreach(_.reset := dut_reset)

  dut.dontTouchPorts()
  dut.tieOffInterrupts()
  SimAXIMem.connectMem(ldut)
  SimAXIMem.connectMMIO(ldut)
  ldut.l2_frontend_bus_axi4.foreach( a => {
    a.ar.valid := false.B
    a.ar.bits := DontCare
    a.aw.valid := false.B
    a.aw.bits := DontCare
    a.w.valid := false.B
    a.w.bits := DontCare
    a.r.ready := false.B
    a.b.ready := false.B
  })
  //ldut.l2_frontend_bus_axi4.foreach(_.tieoff)
  //Debug.connectDebug(ldut.debug, ldut.resetctrl, ldut.psd, clock, reset.asBool, io.success)
  Debug.tieoffDebug(ldut.debug, ldut.resetctrl, Some(ldut.psd))
}

object main extends App {
  def getClassFullName(className: String): Option[String] = {
    try {
      val classSymbol = currentMirror.staticClass(className)
      Some(classSymbol.fullName)
    } catch {
      case _: ScalaReflectionException => None
    }
  }
  
  def extractBootromToTempFile(file: String) = {
    val resourceStream: InputStream = getClass.getResourceAsStream("/bootrom.img")
    if (resourceStream == null) {
      throw new RuntimeException("bootrom.img not found in resources")
    }
    val outputStream = new FileOutputStream(file)
    try {
      val buffer = new Array[Byte](1024)
      var bytesRead = resourceStream.read(buffer)
      while (bytesRead != -1) {
        outputStream.write(buffer, 0, bytesRead)
        bytesRead = resourceStream.read(buffer)
      }
    } finally {
      resourceStream.close()
      outputStream.close()
    }
  }
  @main def elaborate(
    @arg(short = 'o', name = "out", doc = "output directory")
    out: String,
    @arg(positional=true, doc = "which core to use (TinyRocket, SmallRocket, MedRocket, BigRocket, HugeRocket, SmallBoom, MediumBoom, LargeBoom, MegaBoom, GigaBoom)")
    core: String = "SmallRocket",
    @arg(short = 'n', name = "ncores", doc = "number of cores")
    ncores: Int = 1,
    @arg(name = "no-dtm", doc = "whether to disable DTM")
    no_dtm: Flag
  ) = {
    val dir = Paths.get(out).toAbsolutePath().toString
    val dirPath = os.Path(dir)
    if (!os.exists(dirPath)) {
      os.makeDir.all(dirPath)
    }
    val bootromPath = (dirPath / "bootrom.img").toString
    extractBootromToTempFile(bootromPath)
    
    val config = core match {
      case "TinyRocket"  => new Config(new OverrideBootromLocation(bootromPath) ++ new With1TinyCore ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "SmallRocket" => new Config(new OverrideBootromLocation(bootromPath) ++ new WithNSmallCores(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "MedRocket"   => new Config(new OverrideBootromLocation(bootromPath) ++ new WithNMedCores(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "BigRocket"   => new Config(new OverrideBootromLocation(bootromPath) ++ new WithNBigCores(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "HugeRocket"  => new Config(new OverrideBootromLocation(bootromPath) ++ new WithNHugeCores(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "SmallBoom"   => new Config(new OverrideBootromLocation(bootromPath) ++ new WithNSmallBooms(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "MediumBoom"  => new Config(new OverrideBootromLocation(bootromPath) ++ new WithNMediumBooms(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "LargeBoom"   => new Config(new OverrideBootromLocation(bootromPath) ++ new WithNLargeBooms(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "MegaBoom"    => new Config(new OverrideBootromLocation(bootromPath) ++ new WithNMegaBooms(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case "GigaBoom"    => new Config(new OverrideBootromLocation(bootromPath) ++ new WithNGigaBooms(ncores) ++ new WithCoherentBusTopology ++ new BaseConfig)
      case _ => throw new Exception(s"Unknown core type: $core")
    }
    val config_name = s"$core-$ncores"
    var topName: String = null
    val gen = () => if(no_dtm.value) {
      new TestHarnessNoDTM()(config)
    } else {
      new TestHarness()(config)
    }
    // Create output directory if it doesn't exist
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
