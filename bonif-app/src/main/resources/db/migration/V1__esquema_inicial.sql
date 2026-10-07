-- Distribuidoras y las credenciales con las que las tiendas nos llaman.
--
-- Esto estaba en variables de entorno hasta la v0.3.0. Era un andamio: escala a ~1000
-- distribuidoras, y 1000 x (usuario, clave, api-key) no entra en un .env ni soporta un alta sin
-- reiniciar el servicio.

CREATE TABLE distribuidora (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,

    -- El codigo del tenant: lo que va en la URL (/v1/dyssa/...) y de lo que salen el host y el
    -- realm por convencion. La base es case-sensitive a proposito, asi que 'dyssa' y 'Dyssa' son
    -- distintos y no se mezclan en silencio.
    codigo          VARCHAR(50)   NOT NULL,
    nombre          VARCHAR(200)  NULL,

    -- host y realm salen de `codigo` (https://<codigo>.gescom.online y gcw-<codigo>), verificado
    -- en dyssa y senderolaser. Se guardan igual, NULL = usar la convencion: con 1000
    -- distribuidoras alguna no va a seguirla, y descubrirlo no puede implicar un release.
    host            VARCHAR(255)  NULL,
    realm           VARCHAR(100)  NULL,

    gescom_usuario  VARCHAR(100)  NOT NULL,
    -- Cifrada con AES-256-GCM (ver CifradoDeSecretos). Es una clave AJENA: hay que poder leerla
    -- en claro para mintear el token, asi que se cifra, no se hashea.
    gescom_clave    VARCHAR(512)  NOT NULL,

    activa          BIT           NOT NULL CONSTRAINT df_distribuidora_activa DEFAULT 1,
    creada_en       DATETIME2(3)  NOT NULL CONSTRAINT df_distribuidora_creada DEFAULT SYSUTCDATETIME(),
    actualizada_en  DATETIME2(3)  NOT NULL CONSTRAINT df_distribuidora_actualizada DEFAULT SYSUTCDATETIME(),

    CONSTRAINT uq_distribuidora_codigo UNIQUE (codigo)
);
GO

CREATE TABLE credencial (
    id                BIGINT IDENTITY(1,1) PRIMARY KEY,
    distribuidora_id  BIGINT        NOT NULL,

    -- Hash SHA-256 en hexa, NO la clave. Es NUESTRA clave: nunca hace falta recuperarla, solo
    -- compararla. Si alguien se queda sin ella, se regenera.
    clave_hash        CHAR(64)      NOT NULL,

    -- VALORIZACION es la que va en la tienda y SOLO puede valorizar. ADMIN no sale del
    -- back-office. Separarlas es lo que limita el dano de una key filtrada en un navegador:
    -- /criterios es la estructura comercial completa y no va con la del checkout.
    alcance           VARCHAR(20)   NOT NULL,
    descripcion       VARCHAR(200)  NULL,

    revocada          BIT           NOT NULL CONSTRAINT df_credencial_revocada DEFAULT 0,
    creada_en         DATETIME2(3)  NOT NULL CONSTRAINT df_credencial_creada DEFAULT SYSUTCDATETIME(),

    CONSTRAINT uq_credencial_hash UNIQUE (clave_hash),
    CONSTRAINT fk_credencial_distribuidora FOREIGN KEY (distribuidora_id)
        REFERENCES distribuidora (id),
    CONSTRAINT ck_credencial_alcance CHECK (alcance IN ('VALORIZACION', 'ADMIN'))
);
GO

-- El lookup de cada request autenticado: por hash, y solo las vigentes.
CREATE INDEX ix_credencial_hash_vigente ON credencial (clave_hash) WHERE revocada = 0;
GO
