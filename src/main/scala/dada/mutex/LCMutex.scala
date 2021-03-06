package dada.mutex

import dada.detail.{TAck, TLogicalClock}
import dada.clock.LogicalClock
import dada.Config._

import dds.pub.{Publisher, DataWriter}
import dds.{DomainParticipant, Topic}
import dds.sub.{DataReader, Subscriber}
import dds.qos._
import dds.event._

import collection.mutable.{SynchronizedPriorityQueue, Map}
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import dada.group.Group
import dada.group.event.MemberFailure

object LCMutex {
  val mutexRequestTopic = Topic[TLogicalClock]("MutexRequest");
  val mutexAckTopic = Topic[TAck]("MutexAck");
  private val subMap = Map[Int, Subscriber]()
  private val pubMap = Map[Int, Publisher]()
  val drQos = DataReaderQos() + History.KeepAll + Reliability.Reliable
  val dwQos = DataWriterQos() + History.KeepAll + Reliability.Reliable


  def groupPublisher(gid: Int): Publisher =
    pubMap.getOrElse (gid,  {
      val qos = PublisherQos(gid.toString)
      val pub = Publisher(mutexRequestTopic.dp, qos)
      pubMap += (gid -> pub)
      pub
    })


  def groupSubscriber(gid: Int): Subscriber = {
    synchronized {
      subMap getOrElse(gid, {
        val qos = SubscriberQos(gid.toString)
        val sub = Subscriber(mutexRequestTopic.dp, qos)
        subMap += (gid -> sub)
        sub
      })
    }
  }
}
/**
 * This is a distributed Mutex implemented levaraging Lamports logical clock algorithm.
 * To be precise the obvious Agrawal optimization on Lamport algorithms is implemented
 * so to use the ACK as a "release".
 *
 * To properly separate the protocol traffic generated by independent Mutex each group
 * runs in its own partition.
 *
 * <b>NOTE:</b> This mutex object is single threaded, meaning that no concurrent
 * acquire release should be issued. In addition notice that this version of the
 * algorithm is not fault-tolerant.
 *
 * @author <a href="mailto:angelo@icorsaro.net">Angelo Corsaro</a>
 * @author <a href="mailto:sara@icorsaro.net">Sara Tucci</a>
 *
 * @version 0.1
 *
 * @param n the numbers of process on the group
 * @param gid the group id associated with this mutex
 */
import dada.clock.LogicalClock._
class LCMutex(val mid: Int, val gid: Int)(implicit val logger: Logger) extends Mutex {

  private var group = Group(gid)
  private var ts = LogicalClock(0, mid)
  private var receivedAcks = new AtomicLong(0)

  private var pendingRequests = new SynchronizedPriorityQueue[LogicalClock]()
  private var myRequest =  LogicalClock.Infinite

  private val reqDW = DataWriter[TLogicalClock](LCMutex.groupPublisher(gid), LCMutex.mutexRequestTopic, LCMutex.dwQos)
  private val reqDR = DataReader[TLogicalClock](LCMutex.groupSubscriber(gid), LCMutex.mutexRequestTopic, LCMutex.drQos)

  private val ackDW = DataWriter[TAck](LCMutex.groupPublisher(gid), LCMutex.mutexAckTopic, LCMutex.dwQos)
  private val ackDR = DataReader[TAck](LCMutex.groupSubscriber(gid), LCMutex.mutexAckTopic, LCMutex.drQos)
  private val ackSemaphore = new Semaphore(0)

  ackDR.reactions += {
    case DataAvailable(dr) => {
      // Count only the ACK for us
      val acks = ((ackDR take) filter (_.amid == mid))
      val k = acks.length

      if (k > 0) {
        // Set the local clock to the max (tsi, tsj) + 1
        synchronized {
          val maxTs = math.max(ts.ts, (acks map (_.ts.ts)).max) + 1
          ts = LogicalClock(maxTs, ts.id)
        }
        val ra = receivedAcks.addAndGet(k)
        val groupSize = group.size
        logger.trace(Console.BLUE +"M" + mid + " received " + ra +" Acks" + Console.RESET)
        logger.trace(Console.BLUE +"M" + mid + " estimated group size: " + groupSize + Console.RESET)
        // If received sufficient many ACKs we can enter our Mutex!
        if (ra == groupSize - 1) {
          receivedAcks.set(0)
          ackSemaphore.release()
        }
      }
    }
  }

  reqDR.reactions += {
    case DataAvailable(dr) => {
      val requests = (reqDR take) filterNot (_.mid == mid)

      if (requests.isEmpty == false ) {
        synchronized {
          val maxTs = math.max((requests map (_.ts)).max, ts.ts) + 1
          ts = LogicalClock(maxTs, ts.id)
        }
        requests foreach (r => {
          if (r < myRequest) {
            ts = ts inc()
            val ack = new TAck(r.mid, ts)
            logger.trace(Console.GREEN +"M."+mid + "  sending ACKs = P"+r.mid +" wiht  TS = " + ts + Console.RESET)
            ackDW ! ack
            None
          }
          else {
            (pendingRequests find (_ == r)).getOrElse({
              pendingRequests.enqueue(r)
              r})
          }
        })
        logger.trace(Console.YELLOW + "-----------------------------------" + Console.RESET)
        logger.trace(Console.YELLOW + "[M."+ mid +"]: Un-Acked Requests Queue" + Console.RESET)
        pendingRequests foreach (r => logger.trace(r.toString))
        logger.trace(Console.YELLOW + "-----------------------------------" + Console.RESET)
        logger.trace(Console.RED + "[M."+ mid +"]: MUTEX onReq Exit" + Console.RESET)
      }
    }
  }

  def acquire() {
    ts = ts.inc()
    myRequest = ts
    logger.trace(Console.RED + "[M."+ mid +"]: ACQUIRE REQ with ts = " + myRequest + Console.RESET)
    reqDW ! myRequest

    // Try to acquire the semaphore and re-issue a request if timeout
    // The assumption is that the system stabilize within the timeout
    // and if we've not received all the ACKS than someone has crashed...
    // Thus we re-try....
    ackSemaphore.acquire()
    logger.trace(Console.RED + "[M."+ mid +"]: Mutex ACQUIRED with ts = " + myRequest + Console.RESET)

  }

  def release() {
    logger.trace(Console.GREEN + "[M."+ mid +"]: Mutex RELEASE" + Console.RESET)
    myRequest = LogicalClock.Infinite
    (pendingRequests dequeueAll) foreach { req =>
      ts = ts inc()
      logger.trace(Console.GREEN +"M."+mid + "  sending ACKs = M."+req.id+" wiht  TS = " + ts + Console.RESET)
      ackDW ! new TAck(req.id, ts)
    }
  }
}
