package referencechip

import Chisel._
import ReferenceChipBackend._
import scala.collection.mutable.HashMap

object ReferenceChipBackend {
  val initMap = new HashMap[Module, Bool]()
}

class ReferenceChipBackend extends VerilogBackend
{
  initMap.clear()
  override def emitPortDef(m: MemAccess, idx: Int) = {
    val res = new StringBuilder()
    for (node <- m.mem.inputs) {
      if(node.name.contains("init"))
         res.append("    .init(" + node.name + "),\n")
    }
    (if (idx == 0) res.toString else "") + super.emitPortDef(m, idx)
  }

  def addMemPin(c: Module) = {
    for (mod <- Module.components; node <- mod.nodes) {
      if (node.isInstanceOf[Mem[ _ ]] && node.component != null && node.asInstanceOf[Mem[_]].seqRead) {
        connectMemPin(c, node.component, node)
      }
    }
  }

  def connectMemPin(topC: Module, c: Module, p: Node): Unit = {
    var isNewPin = false
    val compInitPin = 
      if (initMap.contains(c)) {
        initMap(c)
      } else {
        isNewPin = true
        val res = Bool(INPUT)
        res.isIo = true
        res
      }

    p.inputs += compInitPin

    if (isNewPin) {
      compInitPin.setName("init")
      c.io.asInstanceOf[Bundle] += compInitPin
      compInitPin.component = c
      initMap += (c -> compInitPin)
      connectMemPin(topC, c.parent, compInitPin)
    }
  }

  def addTopLevelPin(c: Module) = {
    val init = Bool(INPUT)
    init.isIo = true
    init.setName("init")
    init.component = c
    c.io.asInstanceOf[Bundle] += init
    initMap += (c -> init)
  }

  transforms += ((c: Module) => addTopLevelPin(c))
  transforms += ((c: Module) => addMemPin(c))
  transforms += ((c: Module) => collectNodesIntoComp(initializeDFS))
}

class Fame1ReferenceChipBackend extends ReferenceChipBackend with Fame1Transform
