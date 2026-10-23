package com.pocketktv.app
import org.junit.Assert.*
import org.junit.Test
class SingingProgressTest {
    private fun played(seconds:Int,accompaniment:Boolean=true)=SingingProgress().also{p->for(i in 0..seconds)p.sample(i*1000L,i*1000L,true,accompaniment)}
    @Test fun completionRequiresRealCoverageAndNaturalEnd(){val p=played(120);assertTrue(p.completed(true,120000));assertTrue(p.accompanimentCompleted(true,120000));assertFalse(p.completed(false,120000));assertFalse(p.completed(true,0))}
    @Test fun seekingToTheEndDoesNotCountAsCompletion(){val p=played(10);p.sample(119000,11000,true,true);p.sample(120000,12000,true,true);assertFalse(p.completed(true,120000));assertTrue(p.playedMs<15000)}
    @Test fun pausesBufferingAndModeSwitchDoNotInventTime(){val p=played(30,false);p.sample(30000,60000,false,true);p.sample(30000,61000,true,true);p.sample(31000,62000,true,true);assertEquals(31000,p.playedMs);assertEquals(1000,p.accompanimentMs);assertFalse(p.accompanimentCompleted(true,120000))}
    @Test fun repeatsDoNotInflateCoverage(){val p=played(60);p.discontinuity();for(i in 0..60)p.sample(i*1000L,61000+i*1000L,true,true);assertEquals(60000,p.coverageMs);assertFalse(p.completed(true,120000))}
    @Test fun copiedEvidenceIsIndependent(){val p=played(10);val copy=p.snapshot();p.sample(11000,11000,true,true);assertEquals(10000,copy.playedMs);assertEquals(11000,p.playedMs)}
}
