/*
 * Copyright (c) 2004-2005 by OpenSymphony
 * All rights reserved.
 * 
 * Previously Copyright (c) 2001-2004 James House
 */
package org.quartz.impl.jdbcjobstore;

import org.quartz.Calendar;
import org.quartz.JobDetail;
import org.quartz.JobPersistenceException;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.SchedulerConfigException;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.core.SchedulingContext;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.spi.TriggerFiredBundle;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * <code>JobStoreTX</code> is meant to be used in a standalone environment.
 * Both commit and rollback will be handled by this class.
 * </p>
 * 
 * <p>
 * If you need a <code>{@link org.quartz.spi.JobStore}</code> class to use
 * within an application-server environment, use <code>{@link
 * org.quartz.impl.jdbcjobstore.JobStoreCMT}</code>
 * instead.
 * </p>
 * 
 * @author <a href="mailto:jeff@binaryfeed.org">Jeffrey Wescott</a>
 * @author James House
 */
public class JobStoreTX extends JobStoreSupport {

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    public void initialize(ClassLoadHelper loadHelper,
            SchedulerSignaler signaler) throws SchedulerConfigException {

        super.initialize(loadHelper, signaler);

        getLog().info("JobStoreTX initialized.");
    }

    //---------------------------------------------------------------------------
    // JobStoreSupport methods
    //---------------------------------------------------------------------------

    /**
     * <p>
     * Recover any failed or misfired jobs and clean up the data store as
     * appropriate.
     * </p>
     * 
     * @throws JobPersistenceException
     *           if jobs could not be recovered
     */
    protected void recoverJobs() throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            recoverJobs(conn);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);

            closeConnection(conn);
        }
    }

    protected void cleanVolatileTriggerAndJobs() throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            cleanVolatileTriggerAndJobs(conn);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    //---------------------------------------------------------------------------
    // job / trigger storage methods
    //---------------------------------------------------------------------------

    /**
     * <p>
     * Store the given <code>{@link org.quartz.JobDetail}</code> and <code>{@link org.quartz.Trigger}</code>.
     * </p>
     * 
     * @param newJob
     *          The <code>JobDetail</code> to be stored.
     * @param newTrigger
     *          The <code>Trigger</code> to be stored.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Job</code> with the same name/group already
     *           exists.
     */
    public void storeJobAndTrigger(SchedulingContext ctxt, JobDetail newJob,
            Trigger newTrigger) throws ObjectAlreadyExistsException,
            JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            if(isLockOnInsert()) {
                getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
                transOwner = true;
                //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);
            }

            if (newJob.isVolatile() && !newTrigger.isVolatile()) {
                JobPersistenceException jpe = new JobPersistenceException(
                        "Cannot associate non-volatile "
                                + "trigger with a volatile job!");
                jpe.setErrorCode(SchedulerException.ERR_CLIENT_ERROR);
                throw jpe;
            }

            storeJob(conn, ctxt, newJob, false);
            storeTrigger(conn, ctxt, newTrigger, newJob, false,
                    Constants.STATE_WAITING, false, false);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.JobDetail}</code>.
     * </p>
     * 
     * @param newJob
     *          The <code>JobDetail</code> to be stored.
     * @param replaceExisting
     *          If <code>true</code>, any <code>Job</code> existing in the
     *          <code>JobStore</code> with the same name & group should be
     *          over-written.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Job</code> with the same name/group already
     *           exists, and replaceExisting is set to false.
     */
    public void storeJob(SchedulingContext ctxt, JobDetail newJob,
            boolean replaceExisting) throws ObjectAlreadyExistsException,
            JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            if(isLockOnInsert() || replaceExisting) {
                getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
                transOwner = true;
                //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);
            }

            storeJob(conn, ctxt, newJob, replaceExisting);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Job}</code> with the given
     * name, and any <code>{@link org.quartz.Trigger}</code> s that reference
     * it.
     * </p>
     * 
     * <p>
     * If removal of the <code>Job</code> results in an empty group, the
     * group should be removed from the <code>JobStore</code>'s list of
     * known group names.
     * </p>
     * 
     * @param jobName
     *          The name of the <code>Job</code> to be removed.
     * @param groupName
     *          The group name of the <code>Job</code> to be removed.
     * @return <code>true</code> if a <code>Job</code> with the given name &
     *         group was found and removed from the store.
     */
    public boolean removeJob(SchedulingContext ctxt, String jobName,
            String groupName) throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            boolean removed = removeJob(conn, ctxt, jobName, groupName, true);
            commitConnection(conn);
            return removed;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Retrieve the <code>{@link org.quartz.JobDetail}</code> for the given
     * <code>{@link org.quartz.Job}</code>.
     * </p>
     * 
     * @param jobName
     *          The name of the <code>Job</code> to be retrieved.
     * @param groupName
     *          The group name of the <code>Job</code> to be retrieved.
     * @return The desired <code>Job</code>, or null if there is no match.
     */
    public JobDetail retrieveJob(SchedulingContext ctxt, String jobName,
            String groupName) throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            JobDetail job = retrieveJob(conn, ctxt, jobName, groupName);
            commitConnection(conn);
            return job;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     * 
     * @param newTrigger
     *          The <code>Trigger</code> to be stored.
     * @param replaceExisting
     *          If <code>true</code>, any <code>Trigger</code> existing in
     *          the <code>JobStore</code> with the same name & group should
     *          be over-written.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Trigger</code> with the same name/group already
     *           exists, and replaceExisting is set to false.
     */
    public void storeTrigger(SchedulingContext ctxt, Trigger newTrigger,
            boolean replaceExisting) throws ObjectAlreadyExistsException,
            JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            if(isLockOnInsert() || replaceExisting) {
                getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
                transOwner = true;
                //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);
            }
            
            storeTrigger(conn, ctxt, newTrigger, null, replaceExisting,
                    STATE_WAITING, false, false);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Trigger}</code> with the
     * given name.
     * </p>
     * 
     * <p>
     * If removal of the <code>Trigger</code> results in an empty group, the
     * group should be removed from the <code>JobStore</code>'s list of
     * known group names.
     * </p>
     * 
     * <p>
     * If removal of the <code>Trigger</code> results in an 'orphaned' <code>Job</code>
     * that is not 'durable', then the <code>Job</code> should be deleted
     * also.
     * </p>
     * 
     * @param triggerName
     *          The name of the <code>Trigger</code> to be removed.
     * @param groupName
     *          The group name of the <code>Trigger</code> to be removed.
     * @return <code>true</code> if a <code>Trigger</code> with the given
     *         name & group was found and removed from the store.
     */
    public boolean removeTrigger(SchedulingContext ctxt, String triggerName,
            String groupName) throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            boolean removed = removeTrigger(conn, ctxt, triggerName, groupName);
            commitConnection(conn);
            return removed;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /** 
     * @see org.quartz.spi.JobStore#replaceTrigger(org.quartz.core.SchedulingContext, java.lang.String, java.lang.String, org.quartz.Trigger)
     */
    public boolean replaceTrigger(SchedulingContext ctxt, String triggerName, String groupName, Trigger newTrigger) throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            boolean removed = replaceTrigger(conn, ctxt, triggerName, groupName, newTrigger);
            commitConnection(conn);
            return removed;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    
    
    /**
     * <p>
     * Retrieve the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     * 
     * @param triggerName
     *          The name of the <code>Trigger</code> to be retrieved.
     * @param groupName
     *          The group name of the <code>Trigger</code> to be retrieved.
     * @return The desired <code>Trigger</code>, or null if there is no
     *         match.
     */
    public Trigger retrieveTrigger(SchedulingContext ctxt, String triggerName,
            String groupName) throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            Trigger trigger = retrieveTrigger(conn, ctxt, triggerName,
                    groupName);
            commitConnection(conn);
            return trigger;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.Calendar}</code>.
     * </p>
     * 
     * @param calName
     *          The name of the calendar.
     * @param calendar
     *          The <code>Calendar</code> to be stored.
     * @param replaceExisting
     *          If <code>true</code>, any <code>Calendar</code> existing
     *          in the <code>JobStore</code> with the same name & group
     *          should be over-written.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Calendar</code> with the same name already
     *           exists, and replaceExisting is set to false.
     */
    public void storeCalendar(SchedulingContext ctxt, String calName,
            Calendar calendar, boolean replaceExisting, boolean updateTriggers)
            throws ObjectAlreadyExistsException, JobPersistenceException {
        Connection conn = getConnection();
        boolean lockOwner = false;
        try {
            if(isLockOnInsert() || updateTriggers) {
                getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
                lockOwner = true;
            }
            
            storeCalendar(conn, ctxt, calName, calendar, replaceExisting, updateTriggers);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, lockOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Calendar}</code> with the
     * given name.
     * </p>
     * 
     * <p>
     * If removal of the <code>Calendar</code> would result in
     * <code.Trigger</code>s pointing to non-existent calendars, then a
     * <code>JobPersistenceException</code> will be thrown.</p>
     *       *
     * @param calName The name of the <code>Calendar</code> to be removed.
     * @return <code>true</code> if a <code>Calendar</code> with the given name
     * was found and removed from the store.
     */
    public boolean removeCalendar(SchedulingContext ctxt, String calName)
            throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            getLockHandler().obtainLock(conn, LOCK_CALENDAR_ACCESS);

            boolean removed = removeCalendar(conn, ctxt, calName);
            commitConnection(conn);
            return removed;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_CALENDAR_ACCESS, true);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Retrieve the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     * 
     * @param calName
     *          The name of the <code>Calendar</code> to be retrieved.
     * @return The desired <code>Calendar</code>, or null if there is no
     *         match.
     */
    public Calendar retrieveCalendar(SchedulingContext ctxt, String calName)
            throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            Calendar cal = retrieveCalendar(conn, ctxt, calName);
            commitConnection(conn);
            return cal;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    //---------------------------------------------------------------------------
    // informational methods
    //---------------------------------------------------------------------------

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.Job}</code> s that are
     * stored in the <code>JobStore</code>.
     * </p>
     */
    public int getNumberOfJobs(SchedulingContext ctxt)
            throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            int numJobs = getNumberOfJobs(conn, ctxt);
            commitConnection(conn);
            return numJobs;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.Trigger}</code> s that are
     * stored in the <code>JobsStore</code>.
     * </p>
     */
    public int getNumberOfTriggers(SchedulingContext ctxt)
            throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            int numTriggers = getNumberOfTriggers(conn, ctxt);
            commitConnection(conn);
            return numTriggers;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.Calendar}</code> s that are
     * stored in the <code>JobsStore</code>.
     * </p>
     */
    public int getNumberOfCalendars(SchedulingContext ctxt)
            throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            int numCals = getNumberOfCalendars(conn, ctxt);
            commitConnection(conn);
            return numCals;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    
    public Set getPausedTriggerGroups(SchedulingContext ctxt) 
        throws JobPersistenceException {

        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            Set groups = getPausedTriggerGroups(conn, ctxt);
            commitConnection(conn);
            return groups;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }
    
    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Job}</code> s that
     * have the given group name.
     * </p>
     * 
     * <p>
     * If there are no jobs in the given group name, the result should be a
     * zero-length array (not <code>null</code>).
     * </p>
     */
    public String[] getJobNames(SchedulingContext ctxt, String groupName)
            throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            String[] jobNames = getJobNames(conn, ctxt, groupName);
            commitConnection(conn);
            return jobNames;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Trigger}</code> s
     * that have the given group name.
     * </p>
     * 
     * <p>
     * If there are no triggers in the given group name, the result should be a
     * zero-length array (not <code>null</code>).
     * </p>
     */
    public String[] getTriggerNames(SchedulingContext ctxt, String groupName)
            throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            String[] triggerNames = getTriggerNames(conn, ctxt, groupName);
            commitConnection(conn);
            return triggerNames;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Job}</code>
     * groups.
     * </p>
     * 
     * <p>
     * If there are no known group names, the result should be a zero-length
     * array (not <code>null</code>).
     * </p>
     */
    public String[] getJobGroupNames(SchedulingContext ctxt)
            throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            String[] groupNames = getJobGroupNames(conn, ctxt);
            commitConnection(conn);
            return groupNames;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Trigger}</code>
     * groups.
     * </p>
     * 
     * <p>
     * If there are no known group names, the result should be a zero-length
     * array (not <code>null</code>).
     * </p>
     */
    public String[] getTriggerGroupNames(SchedulingContext ctxt)
            throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            String[] triggerGroups = getTriggerGroupNames(conn, ctxt);
            commitConnection(conn);
            return triggerGroups;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Calendar}</code> s
     * in the <code>JobStore</code>.
     * </p>
     * 
     * <p>
     * If there are no Calendars in the given group name, the result should be
     * a zero-length array (not <code>null</code>).
     * </p>
     */
    public String[] getCalendarNames(SchedulingContext ctxt)
            throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            String[] calNames = getCalendarNames(conn, ctxt);
            commitConnection(conn);
            return calNames;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Get all of the Triggers that are associated to the given Job.
     * </p>
     * 
     * <p>
     * If there are no matches, a zero-length array should be returned.
     * </p>
     */
    public Trigger[] getTriggersForJob(SchedulingContext ctxt, String jobName,
            String groupName) throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            return getTriggersForJob(conn, ctxt, jobName, groupName);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Get the current state of the identified <code>{@link Trigger}</code>.
     * </p>
     * 
     * @see Trigger#STATE_NORMAL
     * @see Trigger#STATE_PAUSED
     * @see Trigger#STATE_COMPLETE
     * @see Trigger#STATE_ERROR
     * @see Trigger#STATE_NONE
     */
    public int getTriggerState(SchedulingContext ctxt, String triggerName,
            String groupName) throws JobPersistenceException {
        Connection conn = getConnection();
        try {
            // no locks necessary for read...
            return getTriggerState(conn, ctxt, triggerName, groupName);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            closeConnection(conn);
        }
    }

    //---------------------------------------------------------------------------
    // trigger state manipulation methods
    //---------------------------------------------------------------------------

    /**
     * <p>
     * Pause the <code>{@link org.quartz.Trigger}</code> with the given name.
     * </p>
     * 
     * @see #resumeTrigger(SchedulingContext, String, String)
     */
    public void pauseTrigger(SchedulingContext ctxt, String triggerName,
            String groupName) throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            pauseTrigger(conn, ctxt, triggerName, groupName);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Pause all of the <code>{@link org.quartz.Trigger}s</code> in the
     * given group.
     * </p>
     * 
     * @see #resumeTriggerGroup(SchedulingContext, String)
     */
    public void pauseTriggerGroup(SchedulingContext ctxt, String groupName)
            throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            pauseTriggerGroup(conn, ctxt, groupName);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Pause the <code>{@link org.quartz.Job}</code> with the given name - by
     * pausing all of its current <code>Trigger</code>s.
     * </p>
     * 
     * @see #resumeJob(SchedulingContext, String, String)
     */
    public void pauseJob(SchedulingContext ctxt, String jobName,
            String groupName) throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            Trigger[] triggers = getTriggersForJob(conn, ctxt, jobName,
                    groupName);
            for (int j = 0; j < triggers.length; j++) {
                pauseTrigger(conn, ctxt, triggers[j].getName(), triggers[j]
                        .getGroup());
            }

            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Pause all of the <code>{@link org.quartz.Job}s</code> in the given
     * group - by pausing all of their <code>Trigger</code>s.
     * </p>
     * 
     * @see #resumeJobGroup(SchedulingContext, String)
     */
    public void pauseJobGroup(SchedulingContext ctxt, String groupName)
            throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            String[] jobNames = getJobNames(conn, ctxt, groupName);

            for (int i = 0; i < jobNames.length; i++) {
                Trigger[] triggers = getTriggersForJob(conn, ctxt, jobNames[i],
                        groupName);
                for (int j = 0; j < triggers.length; j++) {
                    pauseTrigger(conn, ctxt, triggers[j].getName(), triggers[j]
                            .getGroup());
                }
            }

            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Resume (un-pause) the <code>{@link org.quartz.Trigger}</code> with the
     * given name.
     * </p>
     * 
     * <p>
     * If the <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     * 
     * @see #pauseTrigger(SchedulingContext, String, String)
     */
    public void resumeTrigger(SchedulingContext ctxt, String triggerName,
            String groupName) throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            resumeTrigger(conn, ctxt, triggerName, groupName);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Resume (un-pause) all of the <code>{@link org.quartz.Trigger}s</code>
     * in the given group.
     * </p>
     * 
     * <p>
     * If any <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     * 
     * @see #pauseTriggerGroup(SchedulingContext, String)
     */
    public void resumeTriggerGroup(SchedulingContext ctxt, String groupName)
            throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            resumeTriggerGroup(conn, ctxt, groupName);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Resume (un-pause) the <code>{@link org.quartz.Job}</code> with the
     * given name.
     * </p>
     * 
     * <p>
     * If any of the <code>Job</code>'s<code>Trigger</code> s missed one
     * or more fire-times, then the <code>Trigger</code>'s misfire
     * instruction will be applied.
     * </p>
     * 
     * @see #pauseJob(SchedulingContext, String, String)
     */
    public void resumeJob(SchedulingContext ctxt, String jobName,
            String groupName) throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            Trigger[] triggers = getTriggersForJob(conn, ctxt, jobName,
                    groupName);
            for (int j = 0; j < triggers.length; j++) {
                resumeTrigger(conn, ctxt, triggers[j].getName(), triggers[j]
                        .getGroup());
            }

            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Resume (un-pause) all of the <code>{@link org.quartz.Job}s</code> in
     * the given group.
     * </p>
     * 
     * <p>
     * If any of the <code>Job</code> s had <code>Trigger</code> s that
     * missed one or more fire-times, then the <code>Trigger</code>'s
     * misfire instruction will be applied.
     * </p>
     * 
     * @see #pauseJobGroup(SchedulingContext, String)
     */
    public void resumeJobGroup(SchedulingContext ctxt, String groupName)
            throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            String[] jobNames = getJobNames(conn, ctxt, groupName);

            for (int i = 0; i < jobNames.length; i++) {
                Trigger[] triggers = getTriggersForJob(conn, ctxt, jobNames[i],
                        groupName);
                for (int j = 0; j < triggers.length; j++) {
                    resumeTrigger(conn, ctxt, triggers[j].getName(),
                            triggers[j].getGroup());
                }
            }
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Pause all triggers - equivalent of calling <code>pauseTriggerGroup(group)</code>
     * on every group.
     * </p>
     * 
     * <p>
     * When <code>resumeAll()</code> is called (to un-pause), trigger misfire
     * instructions WILL be applied.
     * </p>
     * 
     * @see #resumeAll(SchedulingContext)
     * @see #pauseTriggerGroup(SchedulingContext, String)
     */
    public void pauseAll(SchedulingContext ctxt) throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            pauseAll(conn, ctxt);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Resume (un-pause) all triggers - equivalent of calling <code>resumeTriggerGroup(group)</code>
     * on every group.
     * </p>
     * 
     * <p>
     * If any <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     * 
     * @see #pauseAll(SchedulingContext)
     */
    public void resumeAll(SchedulingContext ctxt)
            throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            resumeAll(conn, ctxt);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    //---------------------------------------------------------------------------
    // trigger firing methods
    //---------------------------------------------------------------------------

    /**
     * <p>
     * Get a handle to the next trigger to be fired, and mark it as 'reserved'
     * by the calling scheduler.
     * </p>
     * 
     * @see #releaseAcquiredTrigger(SchedulingContext, Trigger)
     */
    public Trigger acquireNextTrigger(SchedulingContext ctxt)
            throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            Trigger trigger = acquireNextTrigger(conn, ctxt);
            commitConnection(conn);
            return trigger;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler no longer plans to
     * fire the given <code>Trigger</code>, that it had previously acquired
     * (reserved).
     * </p>
     */
    public void releaseAcquiredTrigger(SchedulingContext ctxt, Trigger trigger)
            throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            releaseAcquiredTrigger(conn, ctxt, trigger);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler is now firing the
     * given <code>Trigger</code> (executing its associated <code>Job</code>),
     * that it had previously acquired (reserved).
     * </p>
     * 
     * @return null if the trigger or it's job or calendar no longer exist, or
     *         if the trigger was not successfully put into the 'executing'
     *         state.
     */
    public TriggerFiredBundle triggerFired(SchedulingContext ctxt,
            Trigger trigger) throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            TriggerFiredBundle tfb = null;
            JobPersistenceException err = null;
            try {
                tfb = triggerFired(conn, ctxt, trigger);
            } catch (JobPersistenceException jpe) {
                if (jpe.getErrorCode() != SchedulerException.ERR_PERSISTENCE_JOB_DOES_NOT_EXIST)
                        throw jpe;
                err = jpe;
            }

            commitConnection(conn);
            if (err != null) throw err;
            return tfb;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler has completed the
     * firing of the given <code>Trigger</code> (and the execution its
     * associated <code>Job</code>), and that the <code>{@link org.quartz.JobDataMap}</code>
     * in the given <code>JobDetail</code> should be updated if the <code>Job</code>
     * is stateful.
     * </p>
     */
    public void triggeredJobComplete(SchedulingContext ctxt, Trigger trigger,
            JobDetail jobDetail, int triggerInstCode)
            throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            triggeredJobComplete(conn, ctxt, trigger, jobDetail,
                    triggerInstCode);
            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    protected boolean doRecoverMisfires() throws JobPersistenceException {
        Connection conn = getConnection();
        boolean transOwner = false;
        boolean moreToDo = false;
        try {
            getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
            transOwner = true;
            //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);

            try {
                moreToDo = recoverMisfiredJobs(conn, false);
            } catch (Exception e) {
                throw new JobPersistenceException(e.getMessage(), e);
            }

            commitConnection(conn);

            return moreToDo;
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
          
            closeConnection(conn);
        }
    }

    protected boolean doCheckin() throws JobPersistenceException {
        Connection conn = getConnection();

        boolean transOwner = false;
        boolean transStateOwner = false;
        boolean recovered = false;

        try {
            getLockHandler().obtainLock(conn, LOCK_STATE_ACCESS);
            transStateOwner = true;

            List failedRecords = clusterCheckIn(conn);

            if (failedRecords.size() > 0) {
                getLockHandler().obtainLock(conn, LOCK_TRIGGER_ACCESS);
                //getLockHandler().obtainLock(conn, LOCK_JOB_ACCESS);
                transOwner = true;

                clusterRecover(conn, failedRecords);
                recovered = true;
            }

            commitConnection(conn);
        } catch (JobPersistenceException e) {
            rollbackConnection(conn);
            throw e;
        } finally {
            releaseLock(conn, LOCK_TRIGGER_ACCESS, transOwner);
            releaseLock(conn, LOCK_STATE_ACCESS, transStateOwner);
            
            closeConnection(conn);
        }

        firstCheckIn = false;

        return recovered;
    }
}

// EOF