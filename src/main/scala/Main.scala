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
import sifive.blocks.devices.uart.UARTParams
import sifive.blocks.devices.uart.PeripheryUARTKey
import freechips.rocketchip.devices.tilelink.MaskROMLocated
import freechips.rocketchip.devices.tilelink.BootROM
import freechips.rocketchip.devices.tilelink.MaskROM
import freechips.rocketchip.util.DontTouch
import org.chipsalliance.cde.config.Field
import sifive.blocks.devices.uart.HasPeripheryUART
import sifive.blocks.devices.uart.UART

class OverrideBootromLocation(contentFileName: String) extends Config((site, here, up) => {
  case BootROMLocated(InSubsystem) => Some(BootROMParams(contentFileName=contentFileName))
})

class WithUART(baudrate: BigInt = 115200, address: BigInt = 0x10020000, txEntries: Int = 8, rxEntries: Int = 8) extends Config ((site, here, up) => {
  case PeripheryUARTKey => up(PeripheryUARTKey) ++ Seq(
    UARTParams(address = address, nTxEntries = txEntries, nRxEntries = rxEntries, initBaudRate = baudrate))
})

case object WithDTMKey extends Field[Boolean](false)

class WithDTM() extends Config ((site, here, up) => {
  case WithDTMKey => true
})

class RocketSystem(implicit p: Parameters) extends RocketSubsystem
    with HasAsyncExtInterrupts
    with CanHaveMasterAXI4MemPort
    with CanHaveMasterAXI4MMIOPort
    with CanHaveSlaveAXI4Port
    with HasPeripheryUART
{
  // optionally add ROM devices
  // Note that setting BootROMLocated will override the reset_vector for all tiles
  val bootROM  = p(BootROMLocated(location)).map { BootROM.attach(_, this, CBUS) }
  val maskROMs = p(MaskROMLocated(location)).map { MaskROM.attach(_, this, CBUS) }

  override lazy val module = new RocketSystemModuleImp(this)
}

class RocketSystemModuleImp[+L <: RocketSystem](_outer: L) extends RocketSubsystemModuleImp(_outer)
    with HasRTCModuleImp
    with HasExtInterruptsModuleImp
    with DontTouch


class TestHarness()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val success = Output(Bool())
  })

  val ldut = LazyModule(new RocketSystem)
  val dut = Module(ldut.module)

  ldut.io_clocks.get.elements.values.foreach(_.clock := clock)
  // Allow the debug ndreset to reset the dut, but not until the initial reset has completed
  //val dut_reset = (reset.asBool | ldut.debug.map { debug => AsyncResetReg(debug.ndreset) }.getOrElse(false.B)).asBool
  val dut_reset = if(p(WithDTMKey)) {
    reset.asBool
  } else {
    (reset.asBool | ldut.debug.map { debug => AsyncResetReg(debug.ndreset) }.getOrElse(false.B)).asBool
  }
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

  if(p(WithDTMKey)) {
    Debug.connectDebug(ldut.debug, ldut.resetctrl, ldut.psd, clock, reset.asBool, io.success)
  } else {
    Debug.tieoffDebug(ldut.debug, ldut.resetctrl, Some(ldut.psd))
  }
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
    @arg(name = "with-dtm", doc = "whether to enable DTM")
    with_dtm: Flag,
    @arg(name = "with-uart", doc = "whether to enable UART")
    with_uart: Flag
  ) = {
    val dir = Paths.get(out).toAbsolutePath().toString
    val dirPath = os.Path(dir)
    if (!os.exists(dirPath)) {
      os.makeDir.all(dirPath)
    }
    val bootromPath = (dirPath / "bootrom.img").toString
    extractBootromToTempFile(bootromPath)
    var baseconfig = new OverrideBootromLocation(bootromPath) ++ new WithCoherentBusTopology ++ new BaseConfig
    if (with_dtm.value) {
      baseconfig = new WithDTM() ++ baseconfig
    }
    if (with_uart.value) {
      baseconfig = new WithUART() ++ baseconfig
    }
    val config = core match {
      case "TinyRocket"  => new Config(new With1TinyCore ++ baseconfig)
      case "SmallRocket" => new Config(new WithNSmallCores(ncores) ++ baseconfig)
      case "MedRocket"   => new Config(new WithNMedCores(ncores) ++ baseconfig)
      case "BigRocket"   => new Config(new WithNBigCores(ncores) ++ baseconfig)
      case "HugeRocket"  => new Config(new WithNHugeCores(ncores) ++ baseconfig)
      case "SmallBoom"   => new Config(new WithNSmallBooms(ncores) ++ baseconfig)
      case "MediumBoom"  => new Config(new WithNMediumBooms(ncores) ++ baseconfig)
      case "LargeBoom"   => new Config(new WithNLargeBooms(ncores) ++ baseconfig)
      case "MegaBoom"    => new Config(new WithNMegaBooms(ncores) ++ baseconfig)
      case "GigaBoom"    => new Config(new WithNGigaBooms(ncores) ++ baseconfig)
      case _ => throw new Exception(s"Unknown core type: $core")
    }
    val config_name = s"$core-$ncores"
    var topName: String = null
    val gen = () => new TestHarness()(config)
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
