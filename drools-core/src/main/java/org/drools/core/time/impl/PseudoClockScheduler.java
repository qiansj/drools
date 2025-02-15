/*
 * Copyright 2010 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.core.time.impl;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Collection;
import java.util.Date;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.drools.core.common.DroolsObjectInputStream;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.time.InternalSchedulerService;
import org.drools.core.time.Job;
import org.drools.core.time.JobContext;
import org.drools.core.time.JobHandle;
import org.drools.core.time.SessionPseudoClock;
import org.drools.core.time.TimerService;
import org.drools.core.time.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A PseudoClockScheduler is a scheduler based on a user controlled clock 
 * that allows the user to explicitly control current time.
 */
public class PseudoClockScheduler
    implements
    TimerService,
    SessionPseudoClock,
    Externalizable,
    InternalSchedulerService {
    
    private Logger logger = LoggerFactory.getLogger( PseudoClockScheduler.class ); 

    private AtomicLong timer;
    private PriorityQueue<DefaultTimerJobInstance> queue;
    private transient InternalWorkingMemory session;

    private TimerJobFactoryManager jobFactoryManager = DefaultTimerJobFactoryManager.instance;

    private AtomicLong idCounter = new AtomicLong();

    public PseudoClockScheduler() {
        this( null );
    }

    public PseudoClockScheduler(InternalWorkingMemory session) {
        this.timer = new AtomicLong(0);
        this.queue = new PriorityQueue<>();
        this.session = session;
    }

    @SuppressWarnings("unchecked")
    public void readExternal(ObjectInput in) throws IOException,
                                            ClassNotFoundException {
        timer = new AtomicLong( in.readLong() );
        PriorityQueue<DefaultTimerJobInstance> tmp = (PriorityQueue<DefaultTimerJobInstance>) in.readObject();
        if ( tmp != null ) {
            queue = tmp;
        }
        session = ((DroolsObjectInputStream) in).getWorkingMemory();
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeLong( timer.get() );
        // this is a work around to a bug in the object stream code, where it raises exceptions
        // when trying to de-serialize an empty priority queue.
        out.writeObject( queue.isEmpty() ? null : queue );
    }

    public void setTimerJobFactoryManager(TimerJobFactoryManager timerJobFactoryManager) {
        this.jobFactoryManager = timerJobFactoryManager;
    }
    
    public TimerJobFactoryManager getTimerJobFactoryManager() {
        return this.jobFactoryManager;
    }    

    /**
     * @inheritDoc
     * 
     * @see org.kie.api.time.SessionClock#getCurrentTime()
     */
    public long getCurrentTime() {
        return this.timer.get();
    }

    /**
     * @inheritDoc
     *
     * @see org.drools.core.time.TimerService#scheduleJob(Job, JobContext, Trigger)
     */
    public JobHandle scheduleJob(Job job,
                                 JobContext ctx,
                                 Trigger trigger) {

        Date date = trigger.hasNextFireTime();
        if ( date == null ){
            return null;
        }

        DefaultJobHandle jobHandle = new DefaultJobHandle( idCounter.getAndIncrement() );
        TimerJobInstance jobInstance = jobFactoryManager.createTimerJobInstance( job, ctx, trigger, jobHandle, this );
        jobHandle.setTimerJobInstance( jobInstance );
        internalSchedule( jobInstance );
        return jobHandle;
    }

    public void internalSchedule(TimerJobInstance timerJobInstance) {
        jobFactoryManager.addTimerJobInstance(timerJobInstance);
        synchronized (this) {
            queue.add( ( DefaultTimerJobInstance ) timerJobInstance );
        }
    }

    /**
     * @inheritDoc
     * @see org.drools.core.time.TimerService#removeJob(JobHandle)
     */
    public synchronized boolean removeJob(JobHandle jobHandle) {
        jobHandle.setCancel(true);
        jobFactoryManager.removeTimerJobInstance(((DefaultJobHandle) jobHandle).getTimerJobInstance());
        return this.queue.remove((DefaultTimerJobInstance) ((DefaultJobHandle) jobHandle).getTimerJobInstance());
    }

    /**
     * @inheritDoc
     */
    public long advanceTime(long amount,
                            TimeUnit unit) {
        return this.runCallBacksAndIncreaseTimer( unit.toMillis( amount ) );
    }

    public void setStartupTime(long i) {
        this.timer.set( i );
    }

    /**
     * @return the session
     */
    public synchronized InternalWorkingMemory getSession() {
        return session;
    }

    /**
     * @param session the session to set
     */
    public synchronized void setSession(InternalWorkingMemory session) {
        this.session = session;
    }

    @Override
    public synchronized void reset() {
        idCounter.set(0);
        timer.set(0);
        queue.clear();
    }

    @Override
    public void shutdown() {
        // nothing to do
    }

    @SuppressWarnings("unchecked")
    private synchronized long runCallBacksAndIncreaseTimer( long increase ) {
        long endTime = this.timer.get() + increase;
        TimerJobInstance item = queue.peek();
        long fireTime;
        while (item != null && item.getTrigger().hasNextFireTime() != null && (fireTime = item.getTrigger().hasNextFireTime().getTime()) <= endTime) {
            // remove the head
            queue.poll();

            if ( !item.getJobHandle().isCancel() ) {
                try {
                    // set the clock back to the trigger's fire time
                    this.timer.getAndSet(fireTime);
                    // execute the call
                    ((Callable<Void>) item).call();
                } catch (Exception e) {
                    logger.error("Exception running callbacks: ", e);
                }
            }

            // get next head
            item = queue.peek();
        }
        this.timer.set( endTime );
        return this.timer.get();
    }

    public synchronized long getTimeToNextJob() {
        TimerJobInstance item = queue.peek();
        return (item != null) ? item.getTrigger().hasNextFireTime().getTime() - this.timer.get() : -1;
    }

    public Collection<TimerJobInstance> getTimerJobInstances(long id) {
        return jobFactoryManager.getTimerJobInstances();
    }
}
