import org.codehaus.groovy.grails.commons.ApplicationHolder
import kerberos.AuditLogListener
import org.hibernate.SessionFactory
import org.hibernate.event.EventListeners
import org.codehaus.groovy.grails.orm.hibernate.ConfigurableLocalSessionFactoryBean
import org.springframework.beans.factory.config.PropertiesFactoryBean
import org.codehaus.groovy.grails.orm.hibernate.support.ClosureEventTriggeringInterceptor
import org.codehaus.groovy.grails.orm.hibernate.support.SpringLobHandlerDetectorFactoryBean
import kerberos.AuditLogListenerUtil
import org.springframework.web.context.request.RequestContextHolder


class KerberosGrailsPlugin {
    def version = "1.2"
    def grailsVersion = '1.1 > *'
    def author = "Svt"
    def authorEmail = "valentinsvt@hotmail.com"
    def title = "A variation of the audit plugin to fit my needs"
    def description = """ Automatically log change events for domain objects.

    """
    def dependsOn = [:]
    def loadAfter = ['core','hibernate']

    def doWithSpring = {


        if (manager?.hasGrailsPlugin("hibernate")) {
            auditLogListener(AuditLogListener) {
                sessionFactory   = sessionFactory
                verbose          = application.config?.auditLog?.verbose?:false
                transactional    = application.config?.auditLog?.transactional?:false
                sessionAttribute = application.config?.auditLog?.sessionAttribute?:""
                actorKey         = application.config?.auditLog?.actorKey?:""
            }
        }
    }

    def doWithApplicationContext = { applicationContext ->
        // pulls in the bean to inject and init
        AuditLogListener listener = applicationContext.getBean("auditLogListener")
        // allows user to over-ride the maximum length the value stored by the audit logger.
        listener.setActorClosure( application.config?.auditLog?.actorClosure?:AuditLogListenerUtil.actorDefaultGetter )
        listener.init()
        if(application.config?.auditLog?.TRUNCATE_LENGTH) {
            listener.truncateLength = new Long(application.config?.auditLog?.TRUNCATE_LENGTH)
        }
    }

    def doWithWebDescriptor = { xml ->
        // TODO Implement additions to web.xml (optional)
    }

    def doWithDynamicMethods = { ctx ->
        // TODO Implement registering dynamic methods to classes (optional)
    }

    def onChange = { event ->
        // TODO Implement code that is executed when this class plugin class is changed  
        // the event contains: event.application and event.applicationContext objects
    }

    def onApplicationChange = { event ->
        // TODO Implement code that is executed when any class in a GrailsApplication changes
        // the event contain: event.source, event.application and event.applicationContext objects
    }
}
