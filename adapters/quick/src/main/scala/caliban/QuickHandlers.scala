package caliban

import zio.http.{ HandlerAspect, RequestHandler }

final case class QuickHandlers[-R](
  api: RequestHandler[R, Nothing],
  upload: RequestHandler[R, Nothing]
) {

  /**
   * Applies a ZIO HTTP `HandlerAspect` to both the api and upload handlers
   */
  def @@[R1 <: R](aspect: HandlerAspect[R1, Unit]): QuickHandlers[R1] =
    QuickHandlers(
      api = (api @@ aspect).merge,
      upload = (upload @@ aspect).merge
    )

}
