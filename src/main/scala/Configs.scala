// See LICENSE for license details.

package rocketchip

import Chisel._
import junctions._
import uncore._
import rocket._
import rocket.Util._
import groundtest._
import scala.math.max
import DefaultTestSuites._
import cde.{Parameters, Config, Dump, Knob, CDEMatchError}

object ConfigUtils {
  def max_int(values: Int*): Int = {
    values.reduce((a, b) => max(a, b))
  }
}
import ConfigUtils._

class BaseConfig extends Config (
  topDefinitions = { (pname,site,here) => 
    type PF = PartialFunction[Any,Any]
    def findBy(sname:Any):Any = here[PF](site[Any](sname))(pname)
    lazy val internalIOAddrMap: AddrMap = {
      val entries = collection.mutable.ArrayBuffer[AddrMapEntry]()
      entries += AddrMapEntry("debug", MemSize(4096, MemAttr(AddrMapProt.RWX)))
      entries += AddrMapEntry("bootrom", MemSize(4096, MemAttr(AddrMapProt.RX)))
      entries += AddrMapEntry("rtc", MemSize(4096, MemAttr(AddrMapProt.RW)))
      entries += AddrMapEntry("plic", MemRange(0x40000000, 0x4000000, MemAttr(AddrMapProt.RW)))
      entries += AddrMapEntry("prci", MemSize(0x4000000, MemAttr(AddrMapProt.RW)))
      entries += AddrMapEntry("x", MemSize(2048, MemAttr(AddrMapProt.RW)))
      new AddrMap(entries)
    }
    lazy val globalAddrMap = {
      val memBase = 0x80000000L
      val memSize = 0x80000000L
      val extIOBase = 0x60000000L
      val extIOSize = 0x20000000L
      val io = AddrMap(
        AddrMapEntry("int", internalIOAddrMap),
        AddrMapEntry("ext", MemRange(extIOBase, extIOSize, MemAttr(AddrMapProt.RWX))))
      val addrMap = AddrMap(
        AddrMapEntry("io", io),
        AddrMapEntry("mem", MemRange(memBase, memSize, MemAttr(AddrMapProt.RWX, true))))

      Dump("MEM_BASE", addrMap("mem").start)
      Dump("MEM_SIZE", memSize)
      Dump("IO_BASE", addrMap("io:ext").start)
      Dump("IO_SIZE", extIOSize)
      addrMap
    }
    def makeConfigString() = {
      val addrMap = globalAddrMap
      val plicAddr = addrMap("io:int:plic").start
      val prciAddr = addrMap("io:int:prci").start
      val plicInfo = site(PLICKey)
      val xLen = site(XLen)
      val res = new StringBuilder
      res append  "platform {\n"
      res append  "  vendor ucb;\n"
      res append  "  arch rocket;\n"
      res append  "};\n"
      res append  "plic {\n"
      res append s"  priority 0x${plicAddr.toString(16)};\n"
      res append s"  pending 0x${(plicAddr + plicInfo.pendingBase).toString(16)};\n"
      res append s"  ndevs ${plicInfo.nDevices};\n"
      res append  "};\n"
      res append  "rtc {\n"
      res append s"  addr 0x${addrMap("io:int:rtc").start.toString(16)};\n"
      res append  "};\n"
      res append  "ram {\n"
      res append  "  0 {\n"
      res append s"    addr 0x${addrMap("mem").start.toString(16)};\n"
      res append s"    size 0x${addrMap("mem").size.toString(16)};\n"
      res append  "  };\n"
      res append  "};\n"
      res append  "core {\n"
      for (i <- 0 until site(NTiles)) {
        val isa = s"rv${site(XLen)}im${if (site(UseAtomics)) "a" else ""}${if (site(UseFPU)) "fd" else ""}"
        val timecmpAddr = addrMap("io:int:rtc").start + 8*(i+1)
        res append s"  $i {\n"
        res append  "    0 {\n"
        res append s"      isa $isa;\n"
        res append s"      timecmp 0x${timecmpAddr.toString(16)};\n"
        res append s"      ipi 0x${(prciAddr + 4*i).toString(16)};\n"
        res append s"      plic {\n"
        res append s"        m {\n"
        res append s"         ie 0x${(plicAddr + plicInfo.enableAddr(i, 'M')).toString(16)};\n"
        res append s"         thresh 0x${(plicAddr + plicInfo.threshAddr(i, 'M')).toString(16)};\n"
        res append s"         claim 0x${(plicAddr + plicInfo.claimAddr(i, 'M')).toString(16)};\n"
        res append s"        };\n"
        if (site(UseVM)) {
          res append s"        s {\n"
          res append s"         ie 0x${(plicAddr + plicInfo.enableAddr(i, 'S')).toString(16)};\n"
          res append s"         thresh 0x${(plicAddr + plicInfo.threshAddr(i, 'S')).toString(16)};\n"
          res append s"         claim 0x${(plicAddr + plicInfo.claimAddr(i, 'S')).toString(16)};\n"
          res append s"        };\n"
        }
        res append s"      };\n"
        res append  "    };\n"
        res append  "  };\n"
      }
      res append  "};\n"
      res append '\u0000'
      res.toString.getBytes
    }
    lazy val innerDataBits = site(MIFDataBits)
    lazy val innerDataBeats = (8 * site(CacheBlockBytes)) / innerDataBits
    pname match {
      case HtifKey => HtifParameters(
                       width = Dump("HTIF_WIDTH", 16),
                       nSCR = 64,
                       csrDataBits = site(XLen),
                       offsetBits = site(CacheBlockOffsetBits),
                       nCores = site(NTiles))
      //Memory Parameters
      case PAddrBits => 32
      case PgIdxBits => 12
      case PgLevels => if (site(XLen) == 64) 3 /* Sv39 */ else 2 /* Sv32 */
      case PgLevelBits => site(PgIdxBits) - log2Up(site(XLen)/8)
      case VPNBits => site(PgLevels) * site(PgLevelBits)
      case PPNBits => site(PAddrBits) - site(PgIdxBits)
      case VAddrBits => site(VPNBits) + site(PgIdxBits)
      case ASIdBits => 7
      case MIFTagBits => Dump("MIF_TAG_BITS", 5)
      case MIFDataBits => Dump("MIF_DATA_BITS", 64)
      case MIFAddrBits => Dump("MIF_ADDR_BITS",
                               site(PAddrBits) - site(CacheBlockOffsetBits))
      case MIFDataBeats => site(CacheBlockBytes) * 8 / site(MIFDataBits)
      case NastiKey => {
        Dump("MEM_STRB_BITS", site(MIFDataBits) / 8)
        NastiParameters(
          dataBits = Dump("MEM_DATA_BITS", site(MIFDataBits)),
          addrBits = Dump("MEM_ADDR_BITS", site(PAddrBits)),
          idBits = Dump("MEM_ID_BITS", site(MIFTagBits)))
      }
      //Params used by all caches
      case NSets => findBy(CacheName)
      case NWays => findBy(CacheName)
      case RowBits => findBy(CacheName)
      case NTLBEntries => findBy(CacheName)
      case CacheIdBits => findBy(CacheName)
      case SplitMetadata => findBy(CacheName)
      case ICacheBufferWays => Knob("L1I_BUFFER_WAYS")
      case "L1I" => {
        case NSets => Knob("L1I_SETS") //64
        case NWays => Knob("L1I_WAYS") //4
        case RowBits => site(TLKey("L1toL2")).dataBitsPerBeat
        case NTLBEntries => 8
        case CacheIdBits => 0
        case SplitMetadata => false
      }:PF
      case "L1D" => {
        case NSets => Knob("L1D_SETS") //64
        case NWays => Knob("L1D_WAYS") //4
        case RowBits => site(TLKey("L1toL2")).dataBitsPerBeat
        case NTLBEntries => 8
        case CacheIdBits => 0
        case SplitMetadata => false
      }:PF
      case ECCCode => None
      case Replacer => () => new RandomReplacement(site(NWays))
      case AmoAluOperandBits => site(XLen)
      //L1InstCache
      case BtbKey => BtbParameters()
      //L1DataCache
      case WordBits => site(XLen)
      case StoreDataQueueDepth => 17
      case ReplayQueueDepth => 16
      case NMSHRs => Knob("L1D_MSHRS")
      case LRSCCycles => 32 
      //L2 Memory System Params
      case NAcquireTransactors => 7
      case L2StoreDataQueueDepth => 1
      case L2DirectoryRepresentation => new NullRepresentation(site(NTiles))
      case BuildL2CoherenceManager => (id: Int, p: Parameters) =>
        Module(new L2BroadcastHub()(p.alterPartial({
          case InnerTLId => "L1toL2"
          case OuterTLId => "L2toMC" })))
      //Tile Constants
      case BuildTiles => {
        val (rvi, rvu) =
          if (site(XLen) == 64) (rv64i, rv64u)
          else (rv32i, rv32u)
        TestGeneration.addSuites(rvi.map(_("p")))
        TestGeneration.addSuites((if(site(UseVM)) List("v") else List()).flatMap(env => rvu.map(_(env))))
        TestGeneration.addSuite(bmarks)
        List.fill(site(NTiles)){ (r: Bool, p: Parameters) =>
          Module(new RocketTile(resetSignal = r)(p.alterPartial({case TLId => "L1toL2"})))
        }
      }
      case BuildRoCC => Nil
      case RoccNMemChannels => site(BuildRoCC).map(_.nMemChannels).foldLeft(0)(_ + _)
      case RoccNPTWPorts => site(BuildRoCC).map(_.nPTWPorts).foldLeft(0)(_ + _)
      case RoccNCSRs => site(BuildRoCC).map(_.csrs.size).foldLeft(0)(_ + _)
      case NDmaTransactors => 3
      case NDmaXacts => site(NDmaTransactors) * site(NTiles)
      case NDmaClients => site(NTiles)
      //Rocket Core Constants
      case FetchWidth => 1
      case RetireWidth => 1
      case UseVM => true
      case UseUser => true
      case UseDebug => true
      case NBreakpoints => 1
      case UsePerfCounters => true
      case FastLoadWord => true
      case FastLoadByte => false
      case FastMulDiv => true
      case XLen => 64
      case UseFPU => {
        val env = if(site(UseVM)) List("p","v") else List("p")
        if(site(FDivSqrt)) TestGeneration.addSuites(env.map(rv64uf))
        else TestGeneration.addSuites(env.map(rv64ufNoDiv))
        true
      }
      case UseAtomics => {
        val env = if(site(UseVM)) List("p","v") else List("p")
        TestGeneration.addSuites(env.map(if (site(XLen) == 64) rv64ua else rv32ua))
        true
      }
      case NExtInterrupts => 2
      case NExtMMIOAXIChannels => 0
      case NExtMMIOAHBChannels => 0
      case PLICKey => PLICConfig(site(NTiles), site(UseVM), site(NExtInterrupts), 0)
      case DMKey => new DefaultDebugModuleConfig(site(NTiles), site(XLen))
      case FDivSqrt => true
      case SFMALatency => 2
      case DFMALatency => 3
      case CoreInstBits => 32
      case CoreDataBits => site(XLen)
      case NCustomMRWCSRs => 0
      case ResetVector => BigInt(0x1000)
      case MtvecInit => BigInt(0x1010)
      case MtvecWritable => true
      //Uncore Paramters
      case RTCPeriod => 100 // gives 10 MHz RTC assuming 1 GHz uncore clock
      case LNEndpoints => site(TLKey(site(TLId))).nManagers + site(TLKey(site(TLId))).nClients
      case LNHeaderBits => log2Ceil(site(TLKey(site(TLId))).nManagers) +
                             log2Up(site(TLKey(site(TLId))).nClients)
      case ExtraL1Clients => 1 // HTIF // TODO not really a parameter
      case HastiId => "Ext"
      case HastiKey("TL") =>
        HastiParameters(
          addrBits = site(PAddrBits),
          dataBits = site(TLKey(site(TLId))).dataBits / site(TLKey(site(TLId))).dataBeats)
      case HastiKey("Ext") =>
        HastiParameters(
          addrBits = site(PAddrBits),
          dataBits = site(XLen))
      case TLKey("L1toL2") => 
        TileLinkParameters(
          coherencePolicy = new MESICoherence(site(L2DirectoryRepresentation)),
          nManagers = site(NBanksPerMemoryChannel)*site(NMemoryChannels) + 1,
          nCachingClients = site(NTiles),
          nCachelessClients = site(ExtraL1Clients) +
                              site(NTiles) *
                                (1 + (if(site(BuildRoCC).isEmpty) 0
                                      else site(RoccNMemChannels))),
          maxClientXacts = max_int(
              // L1 cache
              site(NMSHRs) + 1,
              // RoCC
              if (site(BuildRoCC).isEmpty) 1 else site(RoccMaxTaggedMemXacts)),
          maxClientsPerPort = if (site(BuildRoCC).isEmpty) 1 else 2,
          maxManagerXacts = site(NAcquireTransactors) + 2,
          dataBeats = innerDataBeats,
          dataBits = site(CacheBlockBytes)*8)
      case TLKey("L2toMC") => 
        TileLinkParameters(
          coherencePolicy = new MEICoherence(
            new NullRepresentation(site(NBanksPerMemoryChannel))),
          nManagers = 1,
          nCachingClients = site(NBanksPerMemoryChannel),
          nCachelessClients = 0,
          maxClientXacts = 1,
          maxClientsPerPort = site(NAcquireTransactors) + 2,
          maxManagerXacts = 1,
          dataBeats = innerDataBeats,
          dataBits = site(CacheBlockBytes)*8)
      case TLKey("Outermost") => site(TLKey("L2toMC")).copy(
        maxClientXacts = site(NAcquireTransactors) + 2,
        maxClientsPerPort = site(NBanksPerMemoryChannel),
        dataBeats = site(MIFDataBeats))
      case TLKey("L2toMMIO") => {
        TileLinkParameters(
          coherencePolicy = new MICoherence(
            new NullRepresentation(site(NBanksPerMemoryChannel))),
          nManagers = globalAddrMap.subMap("io").flatten.size,
          nCachingClients = 0,
          nCachelessClients = 1,
          maxClientXacts = 4,
          maxClientsPerPort = 1,
          maxManagerXacts = 1,
          dataBeats = innerDataBeats,
          dataBits = site(CacheBlockBytes) * 8)
      }
      case TLKey("MMIO_Outermost") => site(TLKey("L2toMMIO")).copy(dataBeats = site(MIFDataBeats))
      case NTiles => Knob("NTILES")
      case NMemoryChannels => Dump("N_MEM_CHANNELS", 1)
      case TMemoryChannels => BusType.AXI
      case NBanksPerMemoryChannel => Knob("NBANKS_PER_MEM_CHANNEL")
      case NOutstandingMemReqsPerChannel => site(NBanksPerMemoryChannel)*(site(NAcquireTransactors)+2)
      case BankIdLSB => 0
      case CacheBlockBytes => Dump("CACHE_BLOCK_BYTES", 64)
      case CacheBlockOffsetBits => log2Up(here(CacheBlockBytes))
      case UseHtifClockDiv => true
      case ConfigString => makeConfigString()
      case GlobalAddrMap => globalAddrMap
      case _ => throw new CDEMatchError
  }},
  knobValues = {
    case "NTILES" => 1
    case "NBANKS_PER_MEM_CHANNEL" => 1
    case "L1D_MSHRS" => 2
    case "L1D_SETS" => 64
    case "L1D_WAYS" => 4
    case "L1I_SETS" => 64
    case "L1I_WAYS" => 4
    case "L1I_BUFFER_WAYS" => false
    case _ => throw new CDEMatchError
  }
)
class DefaultConfig extends Config(new WithBlockingL1 ++ new BaseConfig)

class With2Cores extends Config(knobValues = { case "NTILES" => 2; case _ => throw new CDEMatchError })
class With4Cores extends Config(knobValues = { case "NTILES" => 4; case _ => throw new CDEMatchError })
class With8Cores extends Config(knobValues = { case "NTILES" => 8; case _ => throw new CDEMatchError })

class With2BanksPerMemChannel extends Config(knobValues = { case "NBANKS_PER_MEM_CHANNEL" => 2; case _ => throw new CDEMatchError })
class With4BanksPerMemChannel extends Config(knobValues = { case "NBANKS_PER_MEM_CHANNEL" => 4; case _ => throw new CDEMatchError })
class With8BanksPerMemChannel extends Config(knobValues = { case "NBANKS_PER_MEM_CHANNEL" => 8; case _ => throw new CDEMatchError })

class With2MemoryChannels extends Config(
  (pname,site,here) => pname match {
    case NMemoryChannels => Dump("N_MEM_CHANNELS", 2)
    case _ => throw new CDEMatchError
  }
)
class With4MemoryChannels extends Config(
  (pname,site,here) => pname match {
    case NMemoryChannels => Dump("N_MEM_CHANNELS", 4)
    case _ => throw new CDEMatchError
  }
)
class With8MemoryChannels extends Config(
  (pname,site,here) => pname match {
    case NMemoryChannels => Dump("N_MEM_CHANNELS", 8)
    case _ => throw new CDEMatchError
  }
)

class WithL2Cache extends Config(
  (pname,site,here) => pname match {
    case "L2_CAPACITY_IN_KB" => Knob("L2_CAPACITY_IN_KB")
    case "L2Bank" => {
      case NSets => (((here[Int]("L2_CAPACITY_IN_KB")*1024) /
                        site(CacheBlockBytes)) /
                          (site(NBanksPerMemoryChannel)*site(NMemoryChannels))) /
                            site(NWays)
      case NWays => Knob("L2_WAYS")
      case RowBits => site(TLKey(site(TLId))).dataBitsPerBeat
      case CacheIdBits => log2Ceil(site(NMemoryChannels) * site(NBanksPerMemoryChannel))
      case SplitMetadata => Knob("L2_SPLIT_METADATA")
    }: PartialFunction[Any,Any] 
    case NAcquireTransactors => 2
    case NSecondaryMisses => 4
    case L2DirectoryRepresentation => new FullRepresentation(site(NTiles))
    case BuildL2CoherenceManager => (id: Int, p: Parameters) =>
      Module(new L2HellaCacheBank()(p.alterPartial({
        case CacheId => id
        case CacheName => "L2Bank"
        case InnerTLId => "L1toL2"
        case OuterTLId => "L2toMC"})))
    case L2Replacer => () => new SeqRandom(site(NWays))
    case _ => throw new CDEMatchError
  },
  knobValues = { case "L2_WAYS" => 8; case "L2_CAPACITY_IN_KB" => 2048; case "L2_SPLIT_METADATA" => false; case _ => throw new CDEMatchError }
)

class WithPLRU extends Config(
  (pname, site, here) => pname match {
    case L2Replacer => () => new SeqPLRU(site(NSets), site(NWays))
    case _ => throw new CDEMatchError
  })

class WithL2Capacity2048 extends Config(knobValues = { case "L2_CAPACITY_IN_KB" => 2048; case _ => throw new CDEMatchError })
class WithL2Capacity1024 extends Config(knobValues = { case "L2_CAPACITY_IN_KB" => 1024; case _ => throw new CDEMatchError })
class WithL2Capacity512 extends Config(knobValues = { case "L2_CAPACITY_IN_KB" => 512; case _ => throw new CDEMatchError })
class WithL2Capacity256 extends Config(knobValues = { case "L2_CAPACITY_IN_KB" => 256; case _ => throw new CDEMatchError })
class WithL2Capacity128 extends Config(knobValues = { case "L2_CAPACITY_IN_KB" => 128; case _ => throw new CDEMatchError })
class WithL2Capacity64 extends Config(knobValues = { case "L2_CAPACITY_IN_KB" => 64; case _ => throw new CDEMatchError })

class With1L2Ways extends Config(knobValues = { case "L2_WAYS" => 1; case _ => throw new CDEMatchError })
class With2L2Ways extends Config(knobValues = { case "L2_WAYS" => 2; case _ => throw new CDEMatchError })
class With4L2Ways extends Config(knobValues = { case "L2_WAYS" => 4; case _ => throw new CDEMatchError })

class DefaultL2Config extends Config(new WithL2Cache ++ new BaseConfig)
class DefaultL2FPGAConfig extends Config(new WithL2Capacity64 ++ new WithL2Cache ++ new DefaultFPGAConfig)

class PLRUL2Config extends Config(new WithPLRU ++ new DefaultL2Config)

class WithRV32 extends Config(
  (pname,site,here) => pname match {
    case XLen => 32
    case UseVM => false
    case UseUser => false
    case UseAtomics => false
    case UseFPU => false
    case _ => throw new CDEMatchError
  }
)

class FPGAConfig extends Config (
  (pname,site,here) => pname match {
    case NAcquireTransactors => 4
    case UseHtifClockDiv => false
    case _ => throw new CDEMatchError
  }
)

class WithBlockingL1 extends Config (
  knobValues = {
    case "L1D_MSHRS" => 0
    case _ => throw new CDEMatchError
  }
)

class WithAHB extends Config(
  (pname, site, here) => pname match {
    case TMemoryChannels     => BusType.AHB
    case NExtMMIOAHBChannels => 1
  })

class DefaultFPGAConfig extends Config(new FPGAConfig ++ new BaseConfig)

class SmallConfig extends Config (
    topDefinitions = { (pname,site,here) => pname match {
      case UseFPU => false
      case FastMulDiv => false
      case NTLBEntries => 4
      case BtbKey => BtbParameters(nEntries = 0)
      case StoreDataQueueDepth => 2
      case ReplayQueueDepth => 2
      case NAcquireTransactors => 2
      case _ => throw new CDEMatchError
    }},
  knobValues = {
    case "L1D_SETS" => 64
    case "L1D_WAYS" => 1
    case "L1I_SETS" => 64
    case "L1I_WAYS" => 1
    case "L1D_MSHRS" => 0
    case _ => throw new CDEMatchError
  }
)

class DefaultFPGASmallConfig extends Config(new SmallConfig ++ new DefaultFPGAConfig)

class DefaultRV32Config extends Config(new SmallConfig ++ new WithRV32 ++ new BaseConfig)

class ExampleSmallConfig extends Config(new SmallConfig ++ new BaseConfig)

class DualBankConfig extends Config(new With2BanksPerMemChannel ++ new BaseConfig)
class DualBankL2Config extends Config(
  new With2BanksPerMemChannel ++ new WithL2Cache ++ new BaseConfig)

class DualChannelConfig extends Config(new With2MemoryChannels ++ new BaseConfig)
class DualChannelL2Config extends Config(
  new With2MemoryChannels ++ new WithL2Cache ++ new BaseConfig)

class DualChannelDualBankConfig extends Config(
  new With2MemoryChannels ++ new With2BanksPerMemChannel ++ new BaseConfig)
class DualChannelDualBankL2Config extends Config(
  new With2MemoryChannels ++ new With2BanksPerMemChannel ++
  new WithL2Cache ++ new BaseConfig)

class WithRoccExample extends Config(
  (pname, site, here) => pname match {
    case BuildRoCC => Seq(
      RoccParameters(
        opcodes = OpcodeSet.custom0,
        generator = (p: Parameters) => Module(new AccumulatorExample()(p))),
      RoccParameters(
        opcodes = OpcodeSet.custom1,
        generator = (p: Parameters) => Module(new TranslatorExample()(p)),
        nPTWPorts = 1),
      RoccParameters(
        opcodes = OpcodeSet.custom2,
        generator = (p: Parameters) => Module(new CharacterCountExample()(p))))

    case RoccMaxTaggedMemXacts => 1
    case _ => throw new CDEMatchError
  })

class RoccExampleConfig extends Config(new WithRoccExample ++ new BaseConfig)

class WithDmaController extends Config(
  (pname, site, here) => pname match {
    case BuildRoCC => Seq(
        RoccParameters(
          opcodes = OpcodeSet.custom2,
          generator = (p: Parameters) => Module(new DmaController()(p)),
          nPTWPorts = 1,
          csrs = Seq.range(
            DmaCtrlRegNumbers.CSR_BASE,
            DmaCtrlRegNumbers.CSR_END)))
    case RoccMaxTaggedMemXacts => 1
    case _ => throw new CDEMatchError
  })

class WithStreamLoopback extends Config(
  (pname, site, here) => pname match {
    case UseStreamLoopback => true
    case StreamLoopbackSize => 128
    case StreamLoopbackWidth => 64
    case _ => throw new CDEMatchError
  })

class DmaControllerConfig extends Config(new WithDmaController ++ new WithStreamLoopback ++ new DefaultL2Config)
class DmaControllerFPGAConfig extends Config(new WithDmaController ++ new WithStreamLoopback ++ new DefaultFPGAConfig)

class SmallL2Config extends Config(
  new With2MemoryChannels ++ new With4BanksPerMemChannel ++
  new WithL2Capacity256 ++ new DefaultL2Config)

class SingleChannelBenchmarkConfig extends Config(new WithL2Capacity256 ++ new DefaultL2Config)
class DualChannelBenchmarkConfig extends Config(new With2MemoryChannels ++ new SingleChannelBenchmarkConfig)
class QuadChannelBenchmarkConfig extends Config(new With4MemoryChannels ++ new SingleChannelBenchmarkConfig)
class OctoChannelBenchmarkConfig extends Config(new With8MemoryChannels ++ new SingleChannelBenchmarkConfig)

class EightChannelConfig extends Config(new With8MemoryChannels ++ new BaseConfig)

class WithSplitL2Metadata extends Config(knobValues = { case "L2_SPLIT_METADATA" => true; case _ => throw new CDEMatchError })
class SplitL2MetadataTestConfig extends Config(new WithSplitL2Metadata ++ new DefaultL2Config)

class DualCoreConfig extends Config(new With2Cores ++ new BaseConfig)
