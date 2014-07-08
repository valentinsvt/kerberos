package kerberos
/**
 * @author shawn hartsock
 *
 * Grails Hibernate Interceptor for logging
 * saves, updates, deletes and acting on
 * individual properties changes... and
 * delegating calls back to the Domain Class
 *
 * This class is based on the post at
 * http://www.hibernate.org/318.html
 *
 * 2008-04-17 created initial version 0.1
 * 2008-04-21 changes for version 0.2 include simpler events
 *  ... config file ... removed 'onUpdate' event.
 * 2008-06-04 added ignore fields feature
 * 2009-07-04 fetches its own session from sessionFactory to avoid transaction munging
 * 2009-09-05 getActor as a closure to allow developers to supply their own security plugins
 * 2009-09-25 rewrite.
 * 2009-10-04 preparing beta release
 * 2010-10-13 add a transactional config so transactions can be manually toggled by a user OR automatically disabled for testing
 */

import kerberos.AuditLogEvent

import grails.util.Environment
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.codehaus.groovy.grails.commons.ConfigurationHolder
import org.hibernate.HibernateException
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
import org.springframework.web.context.request.RequestContextHolder
import org.hibernate.event.*

public class AuditLogListener implements PreDeleteEventListener, PostInsertEventListener, PostUpdateEventListener,PreUpdateEventListener, Initializable {

    public static final Log log = LogFactory.getLog(AuditLogListener.class);
    public static Long TRUNCATE_LENGTH = 255

    /**
     * The verbose flag flips on and off column by column change logging in
     * insert and delete events. If this is true then all columns are logged
     * each as an individual event.
     */
    boolean verbose = true // in Config.groovy auditLog.verbose = true
    boolean transactional = false
    Long truncateLength
    SessionFactory sessionFactory

    String sessionAttribute
    String actorKey
    def kerberosService  = new KerberosService()

    Closure actorClosure
    void setActorClosure(Closure closure) {
        closure.delegate = this
        closure.properties.putAt("log",this.log)
        this.actorClosure = closure
    }

    void init() {
        if( Environment.getCurrent() != Environment.PRODUCTION && ConfigurationHolder.config.auditLog?.transactional == null ) {
            transactional = false
        }


        if(!truncateLength) {
            truncateLength = new Long(TRUNCATE_LENGTH)
        }
        else {

        }
        registerSelf()
    }

    void registerSelf() {
        sessionFactory.eventListeners.with {
            preDeleteEventListeners = addListener(sessionFactory.eventListeners.preDeleteEventListeners)
            postInsertEventListeners = addListener(sessionFactory.eventListeners.postInsertEventListeners)
            postUpdateEventListeners = addListener(sessionFactory.eventListeners.postUpdateEventListeners)
            preUpdateEventListeners = addListener(sessionFactory.eventListeners.preUpdateEventListeners)
        }
    }

    Object[] addListener(final Object[] array) {
        def size = array?.length?:0
        def expanded = new Object[size + 1]
        if(array) {
            System.arraycopy(array, 0, expanded, 0, array.length)
        }
        expanded[-1] = this
        return expanded
    }

    SessionFactory getSessionFactory() {
        this.sessionFactory
    }

    void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory
    }

    /**
     * if verbose is set to 'true' then you get a log event on
     * each individually changed column/field sent to the database
     * with a record of the old value and the new value.
     */
    void setVerbose(final boolean verbose) {
        this.verbose = verbose
    }

    String getActor() {
        def actor = null
        try {
            if(actorClosure) {
                def attr = RequestContextHolder?.getRequestAttributes()
                def session = attr?.session
                if(attr && session) {
                    actor = actorClosure.call(attr,session)
                }
                else { // no session or attributes mean this is invoked from a Service, Quartz Job, or other headless-operation
                    actor = 'system'
                }
            }
        } catch (Exception ex) {
            println "The auditLog.actorClosure threw this exception: ${ex.message}"
            ex.printStackTrace()
            println "The auditLog.actorClosure will be disabled now."
            actorClosure = null
        }
        return actor?.toString()
    }

    def getUri() {
        def attr = RequestContextHolder?.getRequestAttributes()
        return (attr?.currentRequest?.uri?.toString()) ?: null
    }

    // returns true for kerberos entities.
    def isAuditableEntity(event) {
        if (event && event.getEntity()) {
            def entity = event.getEntity()
            if (entity.metaClass.hasProperty(entity, 'auditable') && entity.'auditable') {
                return true
            }
        }
        return false
    }

    /**
     *  we allow user's to specify
     *       static kerberos = [handlersOnly:true]
     *  if they don't want us to log events for them and instead have their own plan.
     */
    boolean callHandlersOnly(entity) {
        if (entity?.metaClass?.hasProperty(entity, 'auditable') && entity?.'auditable') {
            if (entity.auditable instanceof java.util.Map &&
                    entity.auditable.containsKey('handlersOnly')) {
                return (entity.auditable['handlersOnly']) ? true : false
            }
            return false
        }
        return false
    }

    /**
     * The default ignore field list is:  ['version','lastUpdated'] if you want
     * to provide your own ignore list do so by specifying the ignore list like so:
     *
     *   static kerberos = [ignore:['version','lastUpdated','myField']]
     *
     * ... if instead you really want to trigger on version and lastUpdated changes you
     * may specify an empty ignore list ... like so ...
     *
     *   static kerberos = [ignore:[]]
     *
     */
    List ignoreList(entity) {
        def ignore = ['version', 'lastUpdated']
        if (entity?.metaClass?.hasProperty(entity, 'auditable')) {
            if (entity.auditable instanceof java.util.Map && entity.auditable.containsKey('ignore')) {
                println "found an ignore list one this entity ${entity.getClass()}"
                def list = entity.auditable['ignore']
                if (list instanceof java.util.List) {
                    ignore = list
                }
            }
        }
        return ignore
    }

    def getEntityId(event) {
        if (event && event.entity) {
            def entity = event.getEntity()
            if (entity.metaClass.hasProperty(entity, 'id') && entity.'id') {
                return entity.id.toString()
            }
            else {
                // trying complex entity resolution
                return event.getPersister().hasIdentifierProperty() ? event.getPersister().getIdentifier(event.getEntity(), event.getPersister().guessEntityMode(event.getEntity())) : null;
            }
        }
        return null
    }

    /**
     * We must use the preDelete event if we want to capture
     * what the old object was like.
     */
    public boolean onPreDelete(final PreDeleteEvent event) {
        try {

            def audit = isAuditableEntity(event)
            if (audit) {

                def entity = event.getEntity()
                kerberosService.logObject(entity,3,event)

            }

        } catch (HibernateException e) {
            println  "Audit Plugin unable to process DELETE event"
            e.printStackTrace()
        }
        return false
    }

    /**
     * I'm using the post insert event here instead of the pre-insert
     * event so that I can log the id of the new entity after it
     * is saved. That does mean the the insert event handlers
     * can't work the way we want... but really it's the onChange
     * event handler that does the magic for us.
     */
    public void onPostInsert(final PostInsertEvent event) {
        try {

            def audit = isAuditableEntity(event)
            if (audit) {

                def entity = event.getEntity()

                kerberosService.logObject(entity,1,event)

            }

        } catch (HibernateException e) {
            println  "Audit Plugin unable to process INSERT event"
            e.printStackTrace()
        }

        return;
    }

    private def makeMap(String[] names, Object[] state) {
        def map = [:]
        for (int ii = 0; ii < names.length; ii++) {
            map[names[ii]] = state[ii]
        }
        return map
    }

    /**
     * Now we get fancy. Here we want to log changes...
     * specifically we want to know what property changed,
     * when it changed. And what the differences were.
     *
     * This works better from the onPreUpdate event handler
     * but for some reason it won't execute right for me.
     * Instead I'm doing a rather complex mapping to build
     * a pair of state HashMaps that represent the old and
     * new states of the object.
     *
     * The old and new states are passed to the object's
     * 'onChange' event handler. So that a user can work
     * with both sets of values.
     *
     * Needs complex type testing BTW.
     */
    public void onPostUpdate(final PostUpdateEvent event) {
        try {

            def audit = isAuditableEntity(event)
            if (audit) {

                def entity = event.getEntity()

//                kerberosService.logObject(entity,2,event)
//
            }

        } catch (HibernateException e) {
            println  "Audit Plugin unable to process update event (post)"
            e.printStackTrace()
        }
    }

    public boolean onPreUpdate(final PreUpdateEvent event) {
        try {

            def audit = isAuditableEntity(event)
            if (audit) {

                def entity = event.getEntity()

                kerberosService.logObject(entity,2,event)

            }

        } catch (HibernateException e) {
            println  "Audit Plugin unable to process update event (pre) "
            e.printStackTrace()
        }
        return false
    }

    /**
     * Prevent infinate loops of change logging by trapping
     * non-significant changes. Occasionally you can set up
     * a change handler that will create a "trivial" object
     * change that you don't want to trigger another change
     * event. So this feature uses the ignore parameter
     * to provide a list of fields for onChange to ignore.
     */
    private boolean significantChange(entity, oldMap, newMap) {
        def ignore = ignoreList(entity)
        ignore?.each({key ->
            oldMap.remove(key)
            newMap.remove(key)
        })
        boolean changed = false
        oldMap.each({k, v ->
            if (v != newMap[k]) {
                changed = true
            }
        })
        return changed
    }

    /**
     * only if this is an kerberos entity
     */
    private void onChange(final PostUpdateEvent event) {
        def entity = event.getEntity()
        def entityName = entity.getClass().getName()
        def entityId = event.getId()

        // object arrays representing the old and new state
        def oldState = event.getOldState()
        def newState = event.getState()

        def nameMap = event.getPersister().getPropertyNames()
        def oldMap = [:]
        def newMap = [:]

        if (nameMap) {
            for (int ii = 0; ii < newState.length; ii++) {
                if (nameMap[ii]) {
                    if (oldState) oldMap[nameMap[ii]] = oldState[ii]
                    if (newState) newMap[nameMap[ii]] = newState[ii]
                }
            }
        }

        if (!significantChange(entity, oldMap, newMap)) {
            return
        }

        // allow user's to over-ride whether you do auditing for them.
        if (!callHandlersOnly(event.getEntity())) {
            logChanges(newMap, oldMap, event, entityId, 'UPDATE', entityName)
        }
        /**
         * Hibernate only saves changes when there are changes.
         * That means the onUpdate event was the same as the
         * onChange with no parameters. I've eliminated onUpdate.
         */
        executeHandler(event, 'onChange', oldMap, newMap)
        return
    }

    public void initialize(final Configuration config) {
        // TODO Auto-generated method stub
        return
    }

    /**
     * Leans heavily on the "toString()" of a property
     * ... this feels crufty... should be tighter...
     */
    def logChanges(newMap, oldMap, parentObject, persistedObjectId, eventName, className) {
        println "logging changes... "
        AuditLogEvent audit = null
        def persistedObjectVersion = (newMap?.version) ?: oldMap?.version
        if (newMap) newMap.remove('version')
        if (oldMap) oldMap.remove('version')
        if (newMap && oldMap) {
            println "there are new and old values to log"
            newMap.each({key, val ->
                if (val != oldMap[key]) {
                    audit = new AuditLogEvent(
                            actor: this.getActor(),
                            uri: this.getUri(),
                            className: className,
                            eventName: eventName,
                            persistedObjectId: persistedObjectId?.toString(),
                            persistedObjectVersion: persistedObjectVersion?.toLong(),
                            propertyName: key,
                            oldValue: truncate(oldMap[key]),
                            newValue: truncate(newMap[key]),
                    )
                    saveAuditLog(audit)
                }
            })
        }
        else if (newMap && verbose) {
            println "there are new values and logging is verbose ... "
            newMap.each({key, val ->
                audit = new AuditLogEvent(
                        actor: this.getActor(),
                        uri: this.getUri(),
                        className: className,
                        eventName: eventName,
                        persistedObjectId: persistedObjectId?.toString(),
                        persistedObjectVersion: persistedObjectVersion?.toLong(),
                        propertyName: key,
                        oldValue: null,
                        newValue: truncate(val),
                )
                saveAuditLog(audit)
            })
        }
        else if (oldMap && verbose) {
            println "there is only an old map of values available and logging is set to verbose... "
            oldMap.each({key, val ->
                audit = new AuditLogEvent(
                        actor: this.getActor(),
                        uri: this.getUri(),
                        className: className,
                        eventName: eventName,
                        persistedObjectId: persistedObjectId?.toString(),
                        persistedObjectVersion: persistedObjectVersion?.toLong(),
                        propertyName: key,
                        oldValue: truncate(val),
                        newValue: null
                )
                saveAuditLog(audit)
            })
        }
        else {
            println "creating a basic audit logging event object."
            audit = new AuditLogEvent(
                    actor: this.getActor(),
                    uri: this.getUri(),
                    className: className,
                    eventName: eventName,
                    persistedObjectId: persistedObjectId?.toString(),
                    persistedObjectVersion: persistedObjectVersion?.toLong()
            )
            saveAuditLog(audit)
        }
        return
    }

    String truncate(final obj) {
        truncate(obj,truncateLength.toInteger())
    }

    String truncate(final obj, int max) {
        println "trimming object's string representation based on ${max} characters."
        def str = obj?.toString()?.trim()
        return (str?.length() > max) ? str?.substring(0, max) : str
    }

    /**
     * This calls the handlers based on what was passed in to it.
     */
    def executeHandler(event, handler, oldState, newState) {
        println "calling execute handler ... "
        def entity = event.getEntity()
        if (isAuditableEntity(event) && entity.metaClass.hasProperty(entity, handler)) {
            printlne "entity was auditable and had a handler property ${handler}"
            if (oldState && newState) {
                println "there was both an old state and a new state"
                if (entity."${handler}".maximumNumberOfParameters == 2) {
                    println "there are two parameters to the handler so I'm sending old and new value maps"
                    entity."${handler}"(oldState, newState)
                }
                else {
                    println "only one parameter on the closure I'm sending oldMap and newMap as part of a Map parameter"
                    entity."${handler}"([oldMap: oldState, newMap: newState])
                }
            }
            else if (oldState) {
                println "sending old state into ${handler}"
                entity."${handler}"(oldState)
            }
            else if (newState) {
                println "sending new state into ${handler}"
                entity."${handler}"(newState)
            }
        }
        println "... execute handler is finished."
    }

    /**
     * Performs special logging and save actions for AuditLogEvent class. You cannot use
     * the GORM save features to persist this class on transactional databases due to timing
     * conflicts with the session. Due to problems in this routine on some databases
     * this closure has comprehensive trace logging in it for your diagnostics.
     *
     * It has also been written as a closure for your sake so that you may over-ride the
     * save closure with your own code (should your particular database not work with this code)
     * you may over-ride the definition of this closure using ... TODO allow over-ride via config
     *
     * To debug in Config.groovy set:
     *    log4j.debug 'org.codehaus.groovy.grails.plugins.orm.kerberos.AuditLogListener'
     * or
     *    log4j.trace 'org.codehaus.groovy.grails.plugins.orm.kerberos.AuditLogListener'
     *
     * SEE: GRAILSPLUGINS-391
     */
    def saveAuditLog = { AuditLogEvent audit ->
        audit.with {
            dateCreated = new Date()
            lastUpdated = new Date()
        }

        try {
            // NOTE: you simply cannot use the session that GORM is using for some
            // audit log events. That's because some audit events occur *after* a
            // transaction has committed. Late session save actions can invalidate
            // sessions or you can essentially roll-back your audit log saves. This is
            // why you have to open your own session and transaction on some
            // transactional databases and not others.
            Session session = sessionFactory.openSession()
            println "opened new session for audit log persistence"
            def trans = null
            if(transactional) {
                trans = session.beginTransaction()
                println " + began transaction "
            }
            def saved = session.merge(audit)
            println " + saved log entry id:'${saved.id}'."
            session.flush()
            println " + flushed session"
            if(transactional){
                trans?.commit()
                println " + committed transaction"
            }
            session.close()
            println "session closed"
        } catch (org.hibernate.HibernateException ex) {
            println "AuditLogEvent save has failed!"
            println ex.message
            println audit.errors
        }
        return
    }
}
