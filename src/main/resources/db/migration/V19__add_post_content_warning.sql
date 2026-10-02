-- Advertencia de contenido en posts: la persona que publica marca que lo que
-- comparte habla de algo sensible, y el frontend lo muestra difuminado con
-- "Tocá para leer". Protege a quien lee sin censurar a quien escribe.
--
-- Booleano simple a proposito (sin categorias): alcanza para el caso de uso y
-- se puede extender despues sin romper el contrato. DEFAULT false: los posts
-- existentes quedan sin advertencia, igual que antes.
ALTER TABLE posts ADD COLUMN content_warning BOOLEAN NOT NULL DEFAULT false;
