-- Usuarios del panel y de la API de administracion.
--
-- Una clave compartida no alcanza: varias personas van a dar de alta distribuidoras, y hace falta
-- saber QUIEN hizo cada cosa y poder sacarle el acceso a una sola sin cambiarsela a todas. Es la
-- diferencia con MotorFiscal, que tiene una contrasenia unica de panel.

CREATE TABLE usuario (
    id          BIGINT IDENTITY(1,1) PRIMARY KEY,
    usuario     VARCHAR(100)  NOT NULL,
    -- BCrypt. Es una contrasenia que tipea una persona, no un secreto generado: hace falta un
    -- hash lento y con salt, no SHA-256.
    clave_hash  VARCHAR(100)  NOT NULL,
    nombre      VARCHAR(200)  NULL,
    activo      BIT           NOT NULL CONSTRAINT df_usuario_activo DEFAULT 1,
    creado_en   DATETIME2(3)  NOT NULL CONSTRAINT df_usuario_creado DEFAULT SYSUTCDATETIME(),
    creado_por  VARCHAR(100)  NULL,

    CONSTRAINT uq_usuario_usuario UNIQUE (usuario)
);
GO

-- Quien dio de alta y quien toco por ultima vez cada distribuidora. Es el motivo de tener
-- usuarios: cuando una distribuidora quedo mal cargada, se sabe a quien preguntarle.
ALTER TABLE distribuidora ADD creada_por VARCHAR(100) NULL;
GO
ALTER TABLE distribuidora ADD actualizada_por VARCHAR(100) NULL;
GO
