package kerberos


import org.springframework.web.context.request.RequestContextHolder

class KerberosService {

    boolean transactional = true

    def noAuditables = ["constraints", "log", "auditable", "version", "errors", "attached", "metaClass", "mapping", "class", "id", "hasMany", "dirty", "dirtyPropertyNames","validationErrorsMap","gormDynamicFinders","gormPersistentEntity","validationSkipMap","count"]
    def grailsApplication
    def session
    def pack
    //principal servicio de seguridad

    /**
     *
     * @param dominio
     * @param operacion
     * @param event
     * @return
     *
     *@comment operacion debe ser 1 para insert 2 para update 3 para delete


     */
    def logObject(domain,operacion,event){

        def nameMap = event.getPersister().getPropertyNames()
        def session
        def usuario
        def perfil
        def actionName="N.A."
        def controllerName="N.A."
        try{
            session = RequestContextHolder.currentRequestAttributes().getSession()
            usuario = (session.usuarioKerberos)?session.usuarioKerberos:"N/A"
            perfil = (session.perfil)?session.perfil:"N/A"
            actionName=session.an
            controllerName=session.cn
        }catch (e){
            usuario="Sistema"
            perfil="Sistema"
        }


        def old
        def audt
        def listAudt = []
        pack="happy"
        try {
            switch (operacion){
                case 1:
                    //Insert

                    try {
                        audt=new Krbs()
                        audt.usuario=usuario
                        audt.perfil=perfil
                        audt.accion=actionName
                        audt.controlador=controllerName
                        audt.campo=null
                        audt.dominio=domain.class
                        audt.fecha=new Date()
                        audt.operacion="INSERT"
                        audt.registro=domain.id
                        audt.new_value=null
                        audt.old_value=null
                        domain=null
                        if(!audt.save())
                            println "error en el save "+audt.errors


                    } catch (e) {
                        println "ERROR!!!: error en la auditoria "
                        println " audt " + e
                    }

                    break;

                case 2:
                    //update
                    def ignore = noAuditables
                    for (name in nameMap) {
                        def originalValue = domain.getPersistentValue(name)
                        def newValue = domain.properties[name]
                        if(originalValue!=newValue){
                            if (originalValue?.class?.toString() =~ pack ){
                                //println  " name --> "+name+ " original "+originalValue+" clase "+originalValue.class
                                // println "aqui deberia cambiar "
                                originalValue = originalValue?.id
                                newValue = newValue.id
                                //println "original value cambiado "+originalValue+" domain "+newValue
                            }
                            if (!ignore.contains(name)) {
                                audt=new Krbs()
                                audt.usuario=usuario
                                audt.perfil=perfil
                                audt.accion=actionName
                                audt.controlador=controllerName
                                audt.campo=name
                                audt.dominio=domain.class
                                audt.fecha=new Date()
                                audt.operacion="UPDATE"
                                audt.registro=domain.id
                                audt.new_value=newValue
                                audt.old_value=originalValue

                                listAudt.add(audt)
                            }
                        }

                    }//for
                    domain=null
                    listAudt.each {aa->
                        try {

                            if(!aa.save())
                                println "error en el save 24 "+aa.errors

                        } catch (e) {
                            println "ERROR!!!: error en la auditoria 23 "
                            println " audt " + e
                        }

                    }

                    break;
                case 3:

                    old = domain
                    def ignore =  noAuditables
                    def band = true

                    nameMap.each {
                        if (!ignore.contains(it)) {
                            def anterior = old.properties[it]

                            if (anterior && anterior.class.toString() =~ pack) {
                                anterior = anterior.id
                            }
                            if (anterior && anterior.class == java.lang.String) {

                                anterior = (old.properties[it]).replaceAll("\\\n", "").replaceAll("\\\t", "").replaceAll(";", "").replaceAll("'", "").replaceAll("\"", "")
                                if (anterior.size() > 255) {
                                    anterior = anterior[0..254]
                                }

                            }
                            audt=new Krbs()
                            audt.usuario=usuario
                            audt.perfil=perfil
                            audt.accion=actionName
                            audt.controlador=controllerName
                            audt.campo=it
                            audt.dominio=old.class
                            audt.fecha=new Date()
                            audt.operacion="DELETE"
                            audt.registro=old.id
                            audt.new_value="BORRADO"
                            audt.old_value=anterior
                            listAudt.add(audt)
                        }

                    }

                    listAudt.each {aa->
//            println it
                        try {

                            if(!aa.save())
                                println "error en el save 25 "+aa.errors

                        } catch (e) {
                            println "ERROR!!!: error en la auditoria 23 "
                            println " audt " + e
                        }

                    }


                    break;
            }



            domain=null

        } catch (e) {
            println "\n ERROR KERBEROS try log ob " + e
        }

    }




}