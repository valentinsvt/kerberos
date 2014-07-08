package kerberos

class Krbs implements Serializable {
    String usuario
    String perfil
    String accion
    String controlador
    int registro
    String dominio
    String campo
    String old_value
    String new_value
    Date fecha
    String operacion
    static auditable = false
    static mapping = {
        table: 'krbs'
        cache usage:'read-only', include:'non-lazy'
        id generator:'identity'
        version false
        columns {
            id column: 'audt__id'
            usuario column:'usro__id'
            perfil column:'prfl__id'
            accion column: 'audtaccn'
            controlador column: 'audtctrl'
            registro column: 'reg_id'
            dominio column:'audttbla'
            campo column:'audtcmpo'
            old_value column:'audtoldv'
            old_value type: "text"
            new_value column:'audtnewv'
            new_value type: "text"
            fecha column:'audtfcha'
            operacion column: 'audtoprc'
        }
    }
    
    static constraints = {
        usuario(blank:false, nullable:false,size: 1..25)
        fecha(blank:true, nullable:true)
        accion(blank:false, nullable:false)
        perfil(blank:false, nullable:false,size: 1..50)
        controlador(blank:false, nullable:false)
        registro(blank:false, nullable:false)
        dominio(blank:false, nullable:false)
        campo(blank:true, nullable:true)
        old_value(blank:true,nullable:true)
        new_value(blank:true,nullable:true)
        operacion(blank:false, nullable:false)
    }
    
    String toString(){
        return "${this.usuario} ${this.dominio} ${this.operacion}"
    }
    
}
